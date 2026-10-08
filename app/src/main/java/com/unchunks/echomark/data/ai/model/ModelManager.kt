package com.unchunks.echomark.data.ai.model

import android.content.Context
import android.net.Uri
import com.unchunks.echomark.di.DispatcherProvider
import com.unchunks.echomark.domain.repository.BookmarkRepository
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

/**
 * オンデバイス LLM のモデルファイルを管理する。
 * ユーザーが端末内のファイル(SAF で選んだ .task / .litertlm)を `filesDir/models/` へコピーして取り込む。
 * モデルは1つだけ保持し、取り込み直すと置き換える。
 */
@Singleton
class ModelManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bookmarkRepository: BookmarkRepository,
    dispatcherProvider: DispatcherProvider
) {
    // 取り込み・削除の想定外の失敗でアプリを落とさない(取り込みの失敗は importInternal で状態に反映する)
    private val scope = CoroutineScope(
        SupervisorJob() + dispatcherProvider.io +
            CoroutineExceptionHandler { _, e -> Timber.e(e, "モデルの管理処理に失敗") }
    )

    private val copier = SafModelCopier(context)

    private val modelsDir: File
        get() = File(context.filesDir, "models").also { it.mkdirs() }

    private val metadataFile: File get() = File(modelsDir, METADATA_FILE_NAME)

    private val _installedModel = MutableStateFlow(loadMetadata())

    /** 取り込み済みのモデル。未取り込みなら null。 */
    val installedModel: StateFlow<LocalModelInfo?> = _installedModel.asStateFlow()

    private val _importState = MutableStateFlow<ModelImportState>(ModelImportState.Idle)
    val importState: StateFlow<ModelImportState> = _importState.asStateFlow()

    private var importJob: Job? = null

    /** 推論に使うモデルファイル。未取り込み・ファイル欠損なら null。 */
    fun modelFile(): File? {
        val info = _installedModel.value ?: return null
        return File(modelsDir, info.fileName).takeIf { it.isFile && it.length() > 0 }
    }

    fun isAvailable(): Boolean = modelFile() != null

    /**
     * [uri] のファイルを取り込む(アプリのスコープで実行し、画面を離れても続く)。
     * 進捗・結果は [importState] で通知する。取り込み中なら何もしない。
     */
    fun startImport(uri: Uri) {
        if (importJob?.isActive == true) return
        importJob = scope.launch { importInternal(uri) }
    }

    /** 取り込みを中止する。途中まで書いた一時ファイルは削除する。 */
    fun cancelImport() {
        importJob?.cancel()
    }

    /** 成功・失敗の表示が済んだら呼ぶ。 */
    fun clearImportResult() {
        if (_importState.value !is ModelImportState.Copying) _importState.value = ModelImportState.Idle
    }

    /** 取り込んだモデルを削除する。 */
    fun deleteModel() {
        scope.launch {
            _installedModel.value?.let { File(modelsDir, it.fileName).delete() }
            metadataFile.delete()
            _installedModel.value = null
            Timber.d("オンデバイスモデルを削除")
        }
    }

    private suspend fun importInternal(uri: Uri) {
        val part = File(modelsDir, PART_FILE_NAME)
        try {
            val (displayName, size) = copier.queryNameAndSize(uri)
            ModelImportValidator.validate(displayName, size, copier.allocatableBytes(modelsDir))?.let {
                _importState.value = ModelImportState.Failed(it)
                return
            }
            val extension = ModelImportValidator.supportedExtension(displayName)!!

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
            _installedModel.value?.let { File(modelsDir, it.fileName).delete() }
            val dest = File(modelsDir, fileName)
            dest.delete()
            if (!part.renameTo(dest)) throw IOException("rename failed")

            val info = LocalModelInfo(
                fileName = fileName,
                displayName = displayName,
                sizeBytes = copied,
                importedAt = System.currentTimeMillis()
            )
            saveMetadata(info)
            _installedModel.value = info
            _importState.value = ModelImportState.Succeeded(info)
            Timber.i("オンデバイスモデルを取り込み: ${info.displayName} (${info.sizeBytes} bytes)")

            // モデル待ちだったブックマークの AI 処理を再開する
            bookmarkRepository.enqueueWaitingModelProcessing()
        } catch (e: CancellationException) {
            part.delete()
            _importState.value = ModelImportState.Idle
            throw e
        } catch (e: IOException) {
            Timber.w(e, "モデルの取り込みに失敗")
            part.delete()
            val usable = copier.allocatableBytes(modelsDir)
            _importState.value = ModelImportState.Failed(
                // 書き込み中の容量不足(サイズ不明のファイルなど)
                if (usable < ModelImportValidator.STORAGE_MARGIN_BYTES) {
                    ModelImportError.InsufficientStorage(ModelImportValidator.STORAGE_MARGIN_BYTES, usable)
                } else {
                    ModelImportError.ReadFailed
                }
            )
        } catch (e: SecurityException) {
            // 選択後に権限が失効した場合など
            Timber.w(e, "モデルファイルへのアクセスが拒否された")
            part.delete()
            _importState.value = ModelImportState.Failed(ModelImportError.ReadFailed)
        } catch (e: Exception) {
            // 取り込み元のプロバイダが IllegalArgumentException などを投げた場合。コピー中のまま残さない
            Timber.w(e, "モデルの取り込みで想定外のエラー")
            part.delete()
            _importState.value = ModelImportState.Failed(ModelImportError.ReadFailed)
        }
    }

    private fun loadMetadata(): LocalModelInfo? = try {
        val file = metadataFile
        if (!file.isFile) {
            null
        } else {
            val json = JSONObject(file.readText())
            LocalModelInfo(
                fileName = json.getString("fileName"),
                displayName = json.getString("displayName"),
                sizeBytes = json.getLong("sizeBytes"),
                importedAt = json.getLong("importedAt")
            ).takeIf { File(modelsDir, it.fileName).isFile }
        }
    } catch (e: Exception) {
        Timber.w(e, "モデル情報の読み込みに失敗")
        null
    }

    private fun saveMetadata(info: LocalModelInfo) {
        val json = JSONObject()
            .put("fileName", info.fileName)
            .put("displayName", info.displayName)
            .put("sizeBytes", info.sizeBytes)
            .put("importedAt", info.importedAt)
        val tmp = File(modelsDir, "$METADATA_FILE_NAME.tmp")
        tmp.writeText(json.toString())
        if (!tmp.renameTo(metadataFile)) {
            metadataFile.writeText(json.toString())
            tmp.delete()
        }
    }

    private companion object {
        const val MODEL_FILE_BASE_NAME = "local_llm"
        const val METADATA_FILE_NAME = "local_llm.json"
        const val PART_FILE_NAME = "import.part"
    }
}
