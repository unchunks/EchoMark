package com.unchunks.echomark.data.ai.model

import android.content.Context
import com.unchunks.components.core.DispatcherProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** おすすめのモデルのダウンロードの状態。 */
sealed interface CatalogDownloadState {
    data object Idle : CatalogDownloadState

    data class Downloading(
        val entry: ModelCatalogEntry,
        val downloadedBytes: Long,
        /** Wi-Fi の接続を待っているか */
        val waitingForNetwork: Boolean
    ) : CatalogDownloadState

    /** ダウンロードが終わり、確かめて取り込んでいる */
    data class Installing(val entry: ModelCatalogEntry) : CatalogDownloadState

    data class Failed(val entry: ModelCatalogEntry, val message: String) : CatalogDownloadState
}

/** ダウンロードしたファイルを確かめて、端末内モデルの一覧に加える([ModelManager])。 */
fun interface DownloadedModelInstaller {
    /** @return ファイルの大きさと SHA-256 が合わず、加えなかったら false */
    suspend fun install(file: File, entry: ModelCatalogEntry): Boolean
}

/**
 * おすすめのモデルをダウンロードして取り込む。ダウンロードは端末(DownloadManager)に任せ、アプリを閉じても続く。
 * 進み具合はダウンロードの id を保存しておいて、AI の設定画面を開いている間に読む([refresh])。
 * 終わったダウンロードは次に画面を開いたときに取り込む(ダウンロード中に閉じても、取り込み直さずに済む)。同時に行うのは1つだけ。
 * 外部ストレージの一時的な場所から `filesDir/models/` へコピーするため、ファイルの2倍の空きが要る。
 */
@Singleton
class ModelCatalogDownloader internal constructor(
    private val client: ModelDownloadClient,
    private val installer: DownloadedModelInstaller,
    private val activeFile: File,
    private val usableBytes: () -> Long,
    private val dispatcherProvider: DispatcherProvider
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        client: ModelDownloadClient,
        installer: DownloadedModelInstaller,
        dispatcherProvider: DispatcherProvider
    ) : this(
        client,
        installer,
        File(File(context.filesDir, "models"), ACTIVE_FILE_NAME),
        { context.filesDir.usableSpace },
        dispatcherProvider
    )

    private val _state = MutableStateFlow<CatalogDownloadState>(CatalogDownloadState.Idle)
    val state: StateFlow<CatalogDownloadState> = _state.asStateFlow()

    /** 始める・進み具合を読む・取り込むのを重ねない */
    private val mutex = Mutex()

    /** [entry] のダウンロードを始める。ほかのモデルをダウンロード中なら何もしない。 */
    suspend fun start(entry: ModelCatalogEntry): Unit = withContext(dispatcherProvider.io) {
        mutex.withLock {
            if (readActive() != null) return@withLock
            val required = entry.sizeBytes * 2 + ModelImportValidator.STORAGE_MARGIN_BYTES
            val usable = usableBytes()
            if (usable < required) {
                _state.value = CatalogDownloadState.Failed(entry, ModelImportError.InsufficientStorage(required, usable).userMessage)
                return@withLock
            }
            // 前に残ったファイルがあると、別の名前で保存されて見つけられない
            client.fileOf(entry.fileName)?.delete()
            val id = client.enqueue(entry.downloadUrl, entry.name, entry.fileName)
            writeActive(id, entry)
            _state.value = CatalogDownloadState.Downloading(entry, downloadedBytes = 0, waitingForNetwork = false)
        }
    }

    /** ダウンロード中なら、やめて途中のファイルを消す。 */
    suspend fun cancel(): Unit = withContext(dispatcherProvider.io) {
        mutex.withLock {
            readActive()?.let { (id, _) ->
                client.remove(id)
                clearActive()
            }
            _state.value = CatalogDownloadState.Idle
        }
    }

    /** 失敗の表示を消す。 */
    fun clearFailure() {
        if (_state.value is CatalogDownloadState.Failed) _state.value = CatalogDownloadState.Idle
    }

    /** 進み具合を読み直し、終わっていれば確かめて取り込む。 */
    suspend fun refresh(): Unit = withContext(dispatcherProvider.io) {
        mutex.withLock {
            val (id, entry) = readActive() ?: return@withLock
            val progress = client.query(id)
            when (progress?.status) {
                null -> {
                    // 通知などから取り消された
                    clearActive()
                    _state.value = CatalogDownloadState.Idle
                }
                ModelDownloadProgress.Status.RUNNING, ModelDownloadProgress.Status.WAITING_FOR_NETWORK ->
                    _state.value = CatalogDownloadState.Downloading(
                        entry,
                        progress.downloadedBytes,
                        waitingForNetwork = progress.status == ModelDownloadProgress.Status.WAITING_FOR_NETWORK
                    )
                ModelDownloadProgress.Status.FAILED -> finish(id, entry, "ダウンロードできませんでした。通信を確かめて、やり直してください")
                ModelDownloadProgress.Status.SUCCESSFUL -> install(id, entry)
            }
        }
    }

    private suspend fun install(id: Long, entry: ModelCatalogEntry) {
        _state.value = CatalogDownloadState.Installing(entry)
        val file = client.fileOf(entry.fileName)
        val installed = try {
            file != null && installer.install(file, entry)
        } catch (e: CancellationException) {
            // 画面を閉じて止められたら、次に開いたときにやり直す
            throw e
        } catch (e: Exception) {
            Timber.w(e, "ダウンロードしたモデルを取り込めなかった: %s", entry.name)
            false
        }
        finish(id, entry, if (installed) null else "ダウンロードしたファイルを確かめられませんでした。やり直してください")
    }

    private fun finish(id: Long, entry: ModelCatalogEntry, failure: String?) {
        client.remove(id)
        clearActive()
        _state.value = if (failure == null) CatalogDownloadState.Idle else CatalogDownloadState.Failed(entry, failure)
    }

    /** 保存してあるダウンロードの id と、おすすめのモデル。カタログから消えたモデルのものは無いものとする */
    private fun readActive(): Pair<Long, ModelCatalogEntry>? = try {
        if (!activeFile.isFile) {
            null
        } else {
            val json = JSONObject(activeFile.readText())
            val entryId = json.getString(ENTRY_ID)
            ModelCatalog.entries.firstOrNull { it.id == entryId }?.let { json.getLong(DOWNLOAD_ID) to it }
        }
    } catch (e: Exception) {
        Timber.w(e, "ダウンロードの記録を読めない")
        null
    }

    private fun writeActive(id: Long, entry: ModelCatalogEntry) {
        activeFile.parentFile?.mkdirs()
        activeFile.writeText(JSONObject().put(DOWNLOAD_ID, id).put(ENTRY_ID, entry.id).toString())
    }

    private fun clearActive() {
        activeFile.delete()
    }

    private companion object {
        const val ACTIVE_FILE_NAME = "catalog_download.json"
        const val DOWNLOAD_ID = "downloadId"
        const val ENTRY_ID = "entryId"
    }
}
