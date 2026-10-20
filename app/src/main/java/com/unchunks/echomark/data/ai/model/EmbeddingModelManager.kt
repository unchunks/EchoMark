package com.unchunks.echomark.data.ai.model

import android.content.Context
import android.net.Uri
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkManager
import com.unchunks.echomark.di.DispatcherProvider
import com.unchunks.echomark.domain.provider.EmbeddingModelProfile
import com.unchunks.echomark.worker.ReembedAllWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** 取り込み済みの埋め込みモデル。[profile] はユーザーが取り込み時に選んだもの。 */
data class EmbeddingModelInfo(
    val file: LocalModelInfo,
    val profile: EmbeddingModelProfile
)

/**
 * 埋め込みモデルのファイルを管理する。オンデバイス LLM の枠([ModelManager])とは別の枠で、
 * 取り込んだモデルが無いときはアセット同梱のモデル([EmbeddingModelProfile.BUNDLED])を使う。
 *
 * ユーザーが SAF で選んだ `.task` / `.tflite` を `filesDir/models/` へコピーして取り込む。1つだけ保持し、
 * 取り込み直すと置き換える。ファイルの形式からはどのモデルか分からないため、取り込み時に
 * [EmbeddingModelProfile.IMPORTABLE] から選んでもらい、それが保存するベクトルの版としきい値を決める。
 * 取り込み・削除で有効なモデルが変わったら、全ブックマークの埋め込みを作り直す。
 *
 * [ModelManager] は BookmarkRepository に依存するが、BookmarkRepository は EmbeddingProvider に依存するため、
 * 埋め込みの枠は循環を避けて別のクラスにしている(BookmarkRepository には依存しない)。
 */
@Singleton
class EmbeddingModelManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val workManager: WorkManager,
    dispatcherProvider: DispatcherProvider
) {
    private val scope = CoroutineScope(
        SupervisorJob() + dispatcherProvider.io +
            CoroutineExceptionHandler { _, e -> Timber.e(e, "埋め込みモデルの管理処理に失敗") }
    )

    private val copier = SafModelCopier(context)

    private val modelsDir: File
        get() = File(context.filesDir, "models").also { it.mkdirs() }

    private val metadataFile: File get() = File(modelsDir, METADATA_FILE_NAME)

    private val _installedModel = MutableStateFlow(loadMetadata())

    /** 取り込み済みの埋め込みモデル。未取り込み(同梱のモデルを使う)なら null。 */
    val installedModel: StateFlow<EmbeddingModelInfo?> = _installedModel.asStateFlow()

    private val _importState = MutableStateFlow<ModelImportState>(ModelImportState.Idle)
    val importState: StateFlow<ModelImportState> = _importState.asStateFlow()

    private var importJob: Job? = null

    /** いま使う埋め込みモデルのファイル。取り込んだモデルが無い・ファイル欠損なら null(同梱のモデルを使う)。 */
    fun modelFile(): File? {
        val info = _installedModel.value ?: return null
        return File(modelsDir, info.file.fileName).takeIf { it.isFile && it.length() > 0 }
    }

    /**
     * いま使う埋め込みモデルの性質。取り込んだファイルが使えるときだけその profile、それ以外は同梱のもの。
     * ファイルが欠けているのに取り込み時の profile を返すと、同梱のモデルで作ったベクトルに別の版を付けてしまう。
     */
    fun activeProfile(): EmbeddingModelProfile =
        if (modelFile() != null) _installedModel.value!!.profile else EmbeddingModelProfile.BUNDLED

    /**
     * 使うモデルが変わったかを見分けるための識別子。変わったら読み込み済みのモデルを作り直す。
     * 取り込み直すと [LocalModelInfo.importedAt] が変わるので、同じ profile のファイルの入れ替えも検知できる。
     */
    fun sourceKey(): String {
        val info = _installedModel.value
        return if (info != null && modelFile() != null) "file:${info.file.fileName}:${info.file.importedAt}" else "bundled"
    }

    /**
     * [uri] のファイルを [profile] のモデルとして取り込む(アプリのスコープで実行し、画面を離れても続く)。
     * 進捗・結果は [importState] で通知する。取り込み中なら何もしない。
     */
    fun startImport(uri: Uri, profile: EmbeddingModelProfile) {
        if (importJob?.isActive == true) return
        importJob = scope.launch { importInternal(uri, profile) }
    }

    fun cancelImport() {
        importJob?.cancel()
    }

    /** 成功・失敗の表示が済んだら呼ぶ。 */
    fun clearImportResult() {
        if (_importState.value !is ModelImportState.Copying) _importState.value = ModelImportState.Idle
    }

    /** 取り込んだモデルを消して、同梱のモデルに戻す。埋め込みは作り直す。 */
    fun revertToBundled() {
        scope.launch {
            val info = _installedModel.value ?: return@launch
            File(modelsDir, info.file.fileName).delete()
            metadataFile.delete()
            _installedModel.value = null
            Timber.i("取り込んだ埋め込みモデルを削除して同梱のモデルに戻す")
            enqueueReembed()
        }
    }

    private suspend fun importInternal(uri: Uri, profile: EmbeddingModelProfile) {
        val part = File(modelsDir, PART_FILE_NAME)
        try {
            // 一覧に無いプロファイルは取り込まない(版としきい値が決まらないモデルでベクトルを作らない)
            if (profile !in EmbeddingModelProfile.IMPORTABLE) {
                _importState.value = ModelImportState.Failed(ModelImportError.UnknownModelProfile)
                return
            }
            val extensions = ModelImportValidator.EMBEDDING_EXTENSIONS
            val (displayName, size) = copier.queryNameAndSize(uri)
            ModelImportValidator.validate(displayName, size, copier.allocatableBytes(modelsDir), extensions)?.let {
                _importState.value = ModelImportState.Failed(it)
                return
            }
            val extension = ModelImportValidator.supportedExtension(displayName, extensions)!!

            _importState.value = ModelImportState.Copying(0L, size)
            part.delete()
            val copied = copier.copyWithProgress(uri, part) { _importState.value = ModelImportState.Copying(it, size) }
            if (copied == 0L) {
                part.delete()
                _importState.value = ModelImportState.Failed(ModelImportError.EmptyFile)
                return
            }

            // 旧モデルを消してから置き換える(拡張子が変わる場合もあるため固定名 + 拡張子で保存)
            val fileName = MODEL_FILE_BASE_NAME + extension
            _installedModel.value?.let { File(modelsDir, it.file.fileName).delete() }
            val dest = File(modelsDir, fileName)
            dest.delete()
            if (!part.renameTo(dest)) throw IOException("rename failed")

            val info = EmbeddingModelInfo(
                file = LocalModelInfo(
                    fileName = fileName,
                    displayName = displayName,
                    sizeBytes = copied,
                    importedAt = System.currentTimeMillis()
                ),
                profile = profile
            )
            saveMetadata(info)
            _installedModel.value = info
            _importState.value = ModelImportState.Succeeded(info.file)
            Timber.i("埋め込みモデルを取り込み: ${info.file.displayName} (${info.file.sizeBytes} bytes) as ${profile.id}")

            enqueueReembed()
        } catch (e: CancellationException) {
            part.delete()
            _importState.value = ModelImportState.Idle
            throw e
        } catch (e: IOException) {
            Timber.w(e, "埋め込みモデルの取り込みに失敗")
            part.delete()
            val usable = copier.allocatableBytes(modelsDir)
            _importState.value = ModelImportState.Failed(
                if (usable < ModelImportValidator.STORAGE_MARGIN_BYTES) {
                    ModelImportError.InsufficientStorage(ModelImportValidator.STORAGE_MARGIN_BYTES, usable)
                } else {
                    ModelImportError.ReadFailed
                }
            )
        } catch (e: Exception) {
            // SecurityException や取り込み元のプロバイダの不具合など。コピー中のまま残さない
            Timber.w(e, "埋め込みモデルの取り込みで想定外のエラー")
            part.delete()
            _importState.value = ModelImportState.Failed(ModelImportError.ReadFailed)
        }
    }

    /**
     * 有効な埋め込みモデルが変わったので、全ブックマークの埋め込みを作り直す。
     * 起動時(KEEP)と違い、明示的な切り替えなので実行中のものがあれば置き換える(済んだ分は版が合うのでスキップされる)。
     */
    private fun enqueueReembed() {
        ReembedAllWorker.enqueue(workManager, ExistingWorkPolicy.REPLACE)
    }

    private fun loadMetadata(): EmbeddingModelInfo? = try {
        val file = metadataFile
        if (!file.isFile) {
            null
        } else {
            val json = JSONObject(file.readText())
            // 一覧に無い profile(将来削除されたものなど)は、版を決められないので使わない
            val profile = EmbeddingModelProfile.findById(json.getString("profileId"))
            val info = profile?.let {
                EmbeddingModelInfo(
                    file = LocalModelInfo(
                        fileName = json.getString("fileName"),
                        displayName = json.getString("displayName"),
                        sizeBytes = json.getLong("sizeBytes"),
                        importedAt = json.getLong("importedAt")
                    ),
                    profile = it
                )
            }
            info?.takeIf { File(modelsDir, it.file.fileName).isFile }
        }
    } catch (e: Exception) {
        Timber.w(e, "埋め込みモデル情報の読み込みに失敗")
        null
    }

    private fun saveMetadata(info: EmbeddingModelInfo) {
        val json = JSONObject()
            .put("fileName", info.file.fileName)
            .put("displayName", info.file.displayName)
            .put("sizeBytes", info.file.sizeBytes)
            .put("importedAt", info.file.importedAt)
            .put("profileId", info.profile.id)
        val tmp = File(modelsDir, "$METADATA_FILE_NAME.tmp")
        tmp.writeText(json.toString())
        if (!tmp.renameTo(metadataFile)) {
            metadataFile.writeText(json.toString())
            tmp.delete()
        }
    }

    private companion object {
        const val MODEL_FILE_BASE_NAME = "local_embedding"
        const val METADATA_FILE_NAME = "local_embedding.json"
        const val PART_FILE_NAME = "embedding_import.part"
    }
}
