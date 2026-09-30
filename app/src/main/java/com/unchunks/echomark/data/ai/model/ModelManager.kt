package com.unchunks.echomark.data.ai.model

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.unchunks.echomark.di.DispatcherProvider
import com.unchunks.echomark.worker.ModelDownloadWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** モデル1つ分のダウンロード状態。 */
sealed interface ModelState {
    data object NotDownloaded : ModelState
    /** Wi-Fi 接続待ちなど、ダウンロード開始前の待機中 */
    data object Queued : ModelState
    data class Downloading(val percent: Int) : ModelState
    data object Available : ModelState
    data object Failed : ModelState
}

/**
 * モデルファイルの保存場所・存在確認・ダウンロード状態を一元管理する。
 * ファイルは `filesDir/models/` に置く。
 */
@Singleton
class ModelManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val workManager: WorkManager,
    dispatcherProvider: DispatcherProvider
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcherProvider.io)

    private val _states = MutableStateFlow(
        ModelSpecs.all.associate { it.id to initialState(it) }
    )

    /** モデル ID -> 状態。 */
    val states: StateFlow<Map<String, ModelState>> = _states.asStateFlow()

    init {
        ModelSpecs.all.forEach { spec ->
            scope.launch {
                workManager.getWorkInfosForUniqueWorkFlow(workName(spec)).collect { infos ->
                    val state = resolveState(spec, infos)
                    _states.update { it + (spec.id to state) }
                }
            }
        }
    }

    private val modelsDir: File
        get() = File(context.filesDir, "models").also { it.mkdirs() }

    /** 完成したモデルファイル(存在するとは限らない)。 */
    fun file(spec: ModelSpec): File = File(modelsDir, spec.fileName)

    /** ダウンロード途中の一時ファイル。再開(Range)に使う。 */
    fun partFile(spec: ModelSpec): File = File(modelsDir, spec.fileName + ".part")

    fun isAvailable(spec: ModelSpec): Boolean {
        val f = file(spec)
        if (!f.isFile || f.length() == 0L) return false
        return spec.sizeBytes <= 0L || f.length() == spec.sizeBytes
    }

    /** Wi-Fi(従量課金でない回線)接続時にダウンロードを開始する。 */
    fun startDownload(spec: ModelSpec) {
        if (isAvailable(spec)) return
        val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setInputData(workDataOf(ModelDownloadWorker.KEY_MODEL_ID to spec.id))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.UNMETERED)
                    .build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        workManager.enqueueUniqueWork(workName(spec), ExistingWorkPolicy.KEEP, request)
    }

    fun cancelDownload(spec: ModelSpec) {
        workManager.cancelUniqueWork(workName(spec))
    }

    /** ダウンロードを止め、モデルファイルと一時ファイルを削除する。 */
    fun delete(spec: ModelSpec) {
        workManager.cancelUniqueWork(workName(spec))
        scope.launch {
            file(spec).delete()
            partFile(spec).delete()
            Timber.d("モデルを削除: ${spec.id}")
            _states.update { it + (spec.id to ModelState.NotDownloaded) }
        }
    }

    private fun initialState(spec: ModelSpec): ModelState =
        if (isAvailable(spec)) ModelState.Available else ModelState.NotDownloaded

    private fun resolveState(spec: ModelSpec, infos: List<WorkInfo>): ModelState {
        if (isAvailable(spec)) return ModelState.Available
        val active = infos.firstOrNull { !it.state.isFinished }
        if (active != null) {
            return when (active.state) {
                WorkInfo.State.RUNNING ->
                    ModelState.Downloading(active.progress.getInt(ModelDownloadWorker.KEY_PROGRESS, 0))
                else -> ModelState.Queued
            }
        }
        return if (infos.any { it.state == WorkInfo.State.FAILED }) {
            ModelState.Failed
        } else {
            ModelState.NotDownloaded
        }
    }

    private fun workName(spec: ModelSpec) = "model_download_${spec.id}"
}
