package com.unchunks.echomark.data.ai.model

import android.content.Context
import com.unchunks.components.core.DispatcherProvider
import com.unchunks.echomark.data.ai.LocalModelBenchmarker
import com.unchunks.echomark.domain.provider.ModelNotAvailableException
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 端末内モデルの測定を動かし、結果を保持する。モデルと演算装置の組ごとに、最後の結果を残す。
 * アプリのスコープで動かす(画面を離れても続く)。同時に測るのは1つだけ。
 */
@Singleton
class ModelBenchmarkManager internal constructor(
    private val benchmarker: LocalModelBenchmarker,
    private val store: ModelBenchmarkStore,
    dispatcherProvider: DispatcherProvider
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        benchmarker: LocalModelBenchmarker,
        dispatcherProvider: DispatcherProvider
    ) : this(benchmarker, ModelBenchmarkStore(File(File(context.filesDir, "models"), FILE_NAME)), dispatcherProvider)

    private val scope = CoroutineScope(
        SupervisorJob() + dispatcherProvider.io +
            CoroutineExceptionHandler { _, e -> Timber.e(e, "端末内モデルの測定に失敗") }
    )

    private val _state = MutableStateFlow<ModelBenchmarkState>(ModelBenchmarkState.Idle)
    val state: StateFlow<ModelBenchmarkState> = _state.asStateFlow()

    private val _results = MutableStateFlow(store.read())

    /** 測定の結果(モデルと演算装置の組ごとに最後の1件)。 */
    val results: StateFlow<List<ModelBenchmarkResult>> = _results.asStateFlow()

    /** [model] を測る。結果は [results]、進み具合は [state] で知らせる。測っている間は何もしない。 */
    fun start(model: LocalModelInfo) {
        val running = ModelBenchmarkState.Running(model)
        if (!_state.compareAndSet(ModelBenchmarkState.Idle, running) && !retryAfterFailure(running)) return
        scope.launch {
            try {
                val result = benchmarker.benchmark(model.id)
                save(_results.value.filterNot { it.modelId == result.modelId && it.backend == result.backend } + result)
                _state.value = ModelBenchmarkState.Idle
            } catch (e: CancellationException) {
                _state.value = ModelBenchmarkState.Idle
                throw e
            } catch (e: Throwable) {
                // ライブラリの読み込みの失敗やメモリ不足(Error)でも、測定中のまま残さない
                Timber.w(e, "端末内モデルを測れなかった: %s", model.displayName)
                _state.value = ModelBenchmarkState.Failed(model, messageOf(e))
            }
        }
    }

    private fun retryAfterFailure(running: ModelBenchmarkState.Running): Boolean {
        val current = _state.value
        return current is ModelBenchmarkState.Failed && _state.compareAndSet(current, running)
    }

    /** [modelId] の結果を消す(モデルを削除したとき)。 */
    fun forget(modelId: String) {
        val kept = _results.value.filterNot { it.modelId == modelId }
        if (kept.size != _results.value.size) save(kept)
    }

    private fun save(results: List<ModelBenchmarkResult>) {
        _results.update { results }
        store.write(results)
    }

    private fun messageOf(error: Throwable): String = when (error) {
        is ModelNotAvailableException -> "モデルのファイルが見つかりません"
        else -> "測定できませんでした。このモデルはこの端末で動かないか、メモリが足りない可能性があります"
    }

    private companion object {
        const val FILE_NAME = "local_llm_benchmarks.json"
    }
}
