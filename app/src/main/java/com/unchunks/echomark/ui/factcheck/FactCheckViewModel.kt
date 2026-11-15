package com.unchunks.echomark.ui.factcheck

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unchunks.echomark.data.network.isOfflineModeBlock
import com.unchunks.echomark.domain.factcheck.FactCheckProgress
import com.unchunks.echomark.domain.factcheck.FactCheckRepository
import com.unchunks.echomark.domain.factcheck.FactCheckResult
import com.unchunks.echomark.domain.factcheck.FactCheckTarget
import com.unchunks.echomark.domain.factcheck.NothingToFactCheckException
import com.unchunks.echomark.domain.websearch.WebSearchAvailability
import com.unchunks.echomark.domain.websearch.WebSearchException
import com.unchunks.echomark.domain.websearch.WebSearchStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * 「Web で確かめる」のシートの状態。
 * @property target 確かめる対象。null ならシートを閉じている
 * @property text 確かめる文章(要約・回答)
 * @property result 保存した結果(まだ確かめていなければ null)
 * @property progress 確かめている途中の段階(確かめていなければ null)
 * @property error 確かめられなかった理由
 */
data class FactCheckUiState(
    val target: FactCheckTarget? = null,
    val text: String = "",
    val availability: WebSearchAvailability = WebSearchAvailability(),
    val result: FactCheckResult? = null,
    val progress: FactCheckProgress? = null,
    val error: String? = null
) {
    val isRunning: Boolean get() = progress != null

    /** 確かめた後に、要約・回答が変わった */
    val isStale: Boolean get() = result?.isStaleFor(text) == true
}

/**
 * 要約・回答を Web で確かめる(ブックマークの詳細とチャットから使う)。結果は保存し、開き直したときは検索し直さない。
 * 確かめるのは利用者がボタンを押したときだけ(主張の語句が Brave Search に送られるため)。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class FactCheckViewModel @Inject constructor(
    private val repository: FactCheckRepository,
    webSearchStatus: WebSearchStatus
) : ViewModel() {

    private data class Opened(val target: FactCheckTarget, val text: String)

    private val openedFlow = MutableStateFlow<Opened?>(null)
    private val progressFlow = MutableStateFlow<FactCheckProgress?>(null)
    private val errorFlow = MutableStateFlow<String?>(null)
    private var job: Job? = null

    private val resultFlow = openedFlow.flatMapLatest { opened ->
        if (opened == null) flowOf(null) else repository.observe(opened.target)
    }

    val uiState: StateFlow<FactCheckUiState> = combine(
        openedFlow,
        webSearchStatus.availability,
        resultFlow,
        progressFlow,
        errorFlow
    ) { opened, availability, result, progress, error ->
        FactCheckUiState(opened?.target, opened?.text.orEmpty(), availability, result, progress, error)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FactCheckUiState())

    /** シートを開く(保存した結果があれば出す。検索はしない)。 */
    fun open(target: FactCheckTarget, text: String) {
        if (openedFlow.value?.target != target) cancel()
        errorFlow.value = null
        openedFlow.value = Opened(target, text)
    }

    /** シートを閉じる。確かめている途中ならやめる。 */
    fun close() {
        cancel()
        openedFlow.value = null
        errorFlow.value = null
    }

    /** 開いている文章を Web で確かめる(前の結果は置き換える)。 */
    fun check() {
        val opened = openedFlow.value ?: return
        if (job?.isActive == true) return
        errorFlow.value = null
        progressFlow.value = FactCheckProgress.ExtractingClaims
        job = viewModelScope.launch {
            try {
                repository.check(opened.target, opened.text) { progressFlow.value = it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w("Web で確かめられなかった: %s", e.javaClass.simpleName)
                errorFlow.value = failureMessage(e)
            } finally {
                progressFlow.value = null
            }
        }
    }

    private fun cancel() {
        job?.cancel()
        job = null
        progressFlow.value = null
    }

    private fun failureMessage(e: Exception): String = when {
        e.isOfflineModeBlock() -> "オフラインモードのため確かめられません"
        e is WebSearchException -> e.message ?: FAILED
        e is NothingToFactCheckException -> e.message ?: FAILED
        else -> FAILED
    }

    private companion object {
        const val FAILED = "確かめられませんでした。しばらくしてから試してください"
    }
}
