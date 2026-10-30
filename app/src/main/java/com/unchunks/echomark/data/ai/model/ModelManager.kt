package com.unchunks.echomark.data.ai.model

import android.content.Context
import android.net.Uri
import com.unchunks.components.core.DispatcherProvider
import com.unchunks.components.filestorage.SafFileCopier
import com.unchunks.echomark.domain.repository.BookmarkRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** 取り込み済みの端末内モデルの一覧と、既定のモデル。 */
data class InstalledModels(
    /** 取り込んだ順 */
    val models: List<LocalModelInfo> = emptyList(),
    /** 用途ごとにモデルを選んでいないときに使うモデルの id。一覧に無ければ先頭のモデル */
    val defaultId: String? = null
) {
    /** 既定のモデル。1つも無ければ null */
    val default: LocalModelInfo? get() = models.firstOrNull { it.id == defaultId } ?: models.firstOrNull()

    /** [id] のモデル。null か、一覧に無い(削除した)なら既定のモデル */
    fun resolve(id: String?): LocalModelInfo? = id?.let { wanted -> models.firstOrNull { it.id == wanted } } ?: default
}

/**
 * オンデバイス LLM のモデルファイルを管理する。
 * ユーザーが端末内のファイル(SAF で選んだ .litertlm / 旧形式の .task)を `filesDir/models/` へコピーして取り込む。
 * どちらも LiteRT-LM で動かす。取り込むときに、中身が拡張子の形式か(ファイルの先頭のバイト)を確かめる。
 * 複数のモデルを取り込んでおき、用途(要約・タグ付け・チャット)ごとに使うモデルを選べる([AiTaskSetting.localModelId])。
 * 用途で選んでいなければ既定のモデル([InstalledModels.default])を使う。
 * 以前の版の1つだけのモデル(`local_llm.json`)は、最初に読んだときに一覧(`local_llm_models.json`)へ移す。
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

    private val copier = SafFileCopier(context)

    private val modelsDir: File
        get() = File(context.filesDir, "models").also { it.mkdirs() }

    private val listFile: File get() = File(modelsDir, LIST_FILE_NAME)

    /** 一覧の読み書きと、一覧のファイルへの保存をまとめて行うためのロック */
    private val lock = Any()

    private val _installed = MutableStateFlow(loadList())

    /** 取り込み済みのモデルの一覧と既定のモデル。 */
    val installed: StateFlow<InstalledModels> = _installed.asStateFlow()

    private val _installedModel = MutableStateFlow(_installed.value.default)

    /** 既定のモデル。1つも取り込んでいなければ null(端末内 AI を使えるかの判断はこれで足りる)。 */
    val installedModel: StateFlow<LocalModelInfo?> = _installedModel.asStateFlow()

    private val _importState = MutableStateFlow<ModelImportState>(ModelImportState.Idle)
    val importState: StateFlow<ModelImportState> = _importState.asStateFlow()

    private var importJob: Job? = null

    private val _loadFailures = MutableStateFlow<Map<String, LocalModelLoadFailure>>(emptyMap())

    /**
     * モデルの id ごとの、読み込めなかったこと(アプリを起動し直すまで覚える)。読み込めたら・削除したら消える。
     * 同じ id で取り込み直したモデル(中身の違うもの)の失敗は出さない。
     */
    val loadFailures: Flow<Map<String, LocalModelLoadFailure>> =
        combine(_loadFailures, _installed) { failures, installed ->
            failures.filter { (id, failure) -> installed.models.any { it.id == id && it == failure.model } }
        }

    /** [model] のエンジンを作れなかったことを記録する(端末内 AI の推論の側から呼ぶ)。 */
    fun reportLoadFailure(model: LocalModelInfo, error: Throwable) {
        _loadFailures.update { it + (model.id to LocalModelLoadFailure(model, error.message)) }
    }

    /** [model] のエンジンを作れたことを記録する(前の失敗の表示を消す)。 */
    fun reportLoaded(model: LocalModelInfo) {
        _loadFailures.update { failures -> if (failures[model.id]?.model == model) failures - model.id else failures }
    }

    /** [model] のファイル。ファイルが無い・空なら null。 */
    fun modelFile(model: LocalModelInfo): File? =
        File(modelsDir, model.fileName).takeIf { it.isFile && it.length() > 0 }

    /** 既定のモデルのファイル。未取り込み・ファイル欠損なら null。 */
    fun modelFile(): File? = _installed.value.default?.let(::modelFile)

    /** 取り込んだモデルのファイルの合計の大きさ。 */
    fun totalBytes(): Long = _installed.value.models.sumOf { it.sizeBytes }

    /**
     * [uri] のファイルを、モデルの一覧に加える(アプリのスコープで実行し、画面を離れても続く)。
     * 1つ目のモデルは既定のモデルになる。進捗・結果は [importState] で通知する。取り込み中なら何もしない。
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

    /** [id] のモデルを削除する。既定のモデルを削除したら、残りの先頭が既定になる。 */
    fun deleteModel(id: String) {
        scope.launch {
            val removed = synchronized(lock) {
                val current = _installed.value
                val model = current.models.firstOrNull { it.id == id } ?: return@launch
                publish(current.copy(models = current.models - model, defaultId = current.defaultId.takeIf { it != id }))
                model
            }
            File(modelsDir, removed.fileName).delete()
            _loadFailures.update { it - id }
            Timber.d("オンデバイスモデルを削除: %s", removed.displayName)
        }
    }

    /** 用途ごとにモデルを選んでいないときに使うモデルを [id] にする。 */
    fun setDefaultModel(id: String) {
        scope.launch {
            synchronized(lock) {
                val current = _installed.value
                if (current.models.none { it.id == id }) return@launch
                publish(current.copy(defaultId = id))
            }
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
            // 拡張子だけ合う別のファイル(途中で切れたダウンロードの HTML など)は断る
            if (!ModelImportValidator.matchesFormat(extension, readHeader(part))) {
                part.delete()
                _importState.value = ModelImportState.Failed(ModelImportError.InvalidModelFile(extension))
                return
            }

            // モデルごとに別の名前で保存する(取り込んだ時刻で区別する)
            val importedAt = System.currentTimeMillis()
            val id = uniqueId("${MODEL_FILE_BASE_NAME}_$importedAt")
            val fileName = id + extension
            val dest = File(modelsDir, fileName)
            dest.delete()
            if (!part.renameTo(dest)) throw IOException("rename failed")

            val info = LocalModelInfo(
                fileName = fileName,
                displayName = displayName,
                sizeBytes = copied,
                importedAt = importedAt,
                id = id
            )
            synchronized(lock) {
                val current = _installed.value
                publish(current.copy(models = current.models + info))
            }
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

    /** 同じ時刻に取り込んだモデルと id がぶつからないようにする。 */
    private fun uniqueId(base: String): String {
        val used = _installed.value.models.map { it.id }.toSet()
        return generateSequence(0) { it + 1 }.map { if (it == 0) base else "${base}_$it" }.first { it !in used }
    }

    /** 一覧を保存して流す。必ず [lock] を持った状態で呼ぶ。 */
    private fun publish(installed: InstalledModels) {
        saveList(installed)
        _installed.value = installed
        _installedModel.value = installed.default
    }

    private fun readHeader(file: File): ByteArray = file.inputStream().use { input ->
        val buffer = ByteArray(ModelImportValidator.HEADER_BYTES)
        var read = 0
        while (read < buffer.size) {
            val n = input.read(buffer, read, buffer.size - read)
            if (n < 0) break
            read += n
        }
        buffer.copyOf(read)
    }

    /** 一覧を読む。無ければ以前の版の1つだけのモデルの情報を移す。ファイルの無いモデルは除く。 */
    private fun loadList(): InstalledModels = try {
        val installed = if (listFile.isFile) {
            parseList(JSONObject(listFile.readText()))
        } else {
            migrateLegacy()
        }
        installed.copy(models = installed.models.filter { File(modelsDir, it.fileName).isFile })
    } catch (e: Exception) {
        Timber.w(e, "モデル情報の読み込みに失敗")
        InstalledModels()
    }

    private fun parseList(json: JSONObject): InstalledModels {
        val array = json.optJSONArray("models") ?: JSONArray()
        val models = (0 until array.length()).map { i -> modelOf(array.getJSONObject(i)) }
        return InstalledModels(models, json.optString("defaultId").takeIf { it.isNotEmpty() })
    }

    /** 以前の版(モデルは1つだけ)の情報 `local_llm.json` を一覧に移す。ファイル名はそのままで、id はファイル名から作る。 */
    private fun migrateLegacy(): InstalledModels {
        val legacy = File(modelsDir, LEGACY_METADATA_FILE_NAME)
        if (!legacy.isFile) return InstalledModels()
        val model = modelOf(JSONObject(legacy.readText()))
        val installed = InstalledModels(listOf(model), model.id)
        saveList(installed)
        legacy.delete()
        Timber.i("以前の版のモデルの情報を一覧に移した: %s", model.displayName)
        return installed
    }

    private fun modelOf(json: JSONObject): LocalModelInfo {
        val fileName = json.getString("fileName")
        return LocalModelInfo(
            fileName = fileName,
            displayName = json.getString("displayName"),
            sizeBytes = json.getLong("sizeBytes"),
            importedAt = json.getLong("importedAt"),
            id = json.optString("id").takeIf { it.isNotEmpty() } ?: LocalModelInfo.idOf(fileName)
        )
    }

    private fun saveList(installed: InstalledModels) {
        val json = JSONObject()
            .put("models", JSONArray(installed.models.map { model ->
                JSONObject()
                    .put("id", model.id)
                    .put("fileName", model.fileName)
                    .put("displayName", model.displayName)
                    .put("sizeBytes", model.sizeBytes)
                    .put("importedAt", model.importedAt)
            }))
            .apply { installed.defaultId?.let { put("defaultId", it) } }
        val tmp = File(modelsDir, "$LIST_FILE_NAME.tmp")
        tmp.writeText(json.toString())
        if (!tmp.renameTo(listFile)) {
            listFile.writeText(json.toString())
            tmp.delete()
        }
    }

    private companion object {
        const val MODEL_FILE_BASE_NAME = "local_llm"
        const val LIST_FILE_NAME = "local_llm_models.json"

        /** 以前の版(モデルは1つだけ)の情報のファイル */
        const val LEGACY_METADATA_FILE_NAME = "local_llm.json"
        const val PART_FILE_NAME = "import.part"
    }
}
