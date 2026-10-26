package com.unchunks.echomark.ui.discover

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unchunks.echomark.data.discover.DiscoveredResource
import com.unchunks.echomark.data.discover.DiscoveryStep
import com.unchunks.echomark.data.discover.ResourceDiscovery
import com.unchunks.echomark.data.discover.ResourceSource
import com.unchunks.echomark.domain.provider.toLlmUserMessage
import com.unchunks.echomark.domain.repository.AiSetupRepository
import com.unchunks.echomark.domain.repository.AiSetupState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * 資料を探す画面の状態。
 * @property step 探している途中の段(探していなければ null)
 * @property hasSearched 一度でも探したか(探す前は使い方の案内を出す)
 * @property selectedUrls 追加するために選んでいる資料の URL。AI のおすすめは最初から選んだ状態にする
 * @property aiNotice AI に選ばせられなかったときの案内(検索の上位をそのまま出している)
 * @property message 一度だけ出す知らせ(Snackbar)
 */
data class DiscoverUiState(
    val topic: String = "",
    val sources: Set<ResourceSource> = DEFAULT_SOURCES,
    val step: DiscoveryStep? = null,
    val hasSearched: Boolean = false,
    val resources: List<DiscoveredResource> = emptyList(),
    val selectedUrls: Set<String> = emptySet(),
    val failedSources: Set<ResourceSource> = emptySet(),
    val aiNotice: String? = null,
    val errorMessage: String? = null,
    val isSaving: Boolean = false,
    val message: String? = null,
    val aiSetup: AiSetupState = AiSetupState.READY
) {
    val isBusy: Boolean get() = step != null || isSaving
    val canSearch: Boolean get() = topic.isNotBlank() && sources.isNotEmpty() && !isBusy
    val recommended: List<DiscoveredResource> get() = resources.filter { it.isRecommended }
    val others: List<DiscoveredResource> get() = resources.filterNot { it.isRecommended }

    companion object {
        /** 最初に選んでおく検索先。論文(英語)は、英語の語のテーマのときに自分で足してもらう */
        val DEFAULT_SOURCES = setOf(ResourceSource.ARTICLE, ResourceSource.VIDEO, ResourceSource.ENCYCLOPEDIA)
    }
}

/**
 * 資料を探す画面。テーマを入れると Web で資料を探して AI が役立つものを選び、選んだものをまとめてブックマークに追加する。
 */
@HiltViewModel
class DiscoverViewModel @Inject constructor(
    private val discovery: ResourceDiscovery,
    aiSetupRepository: AiSetupRepository
) : ViewModel() {

    private val state = MutableStateFlow(DiscoverUiState())

    val uiState: StateFlow<DiscoverUiState> = combine(state, aiSetupRepository.setupState) { current, setup ->
        current.copy(aiSetup = setup)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DiscoverUiState())

    private var searchJob: Job? = null

    fun onTopicChange(topic: String) {
        state.update { it.copy(topic = topic) }
    }

    /** 検索先を選ぶ・外す。1つも選んでいない状態にはしない。 */
    fun toggleSource(source: ResourceSource) {
        state.update { current ->
            val next = if (source in current.sources) current.sources - source else current.sources + source
            if (next.isEmpty()) current else current.copy(sources = next)
        }
    }

    /** [topic] を入れてから探す(例のテーマを押したとき)。 */
    fun searchTopic(topic: String) {
        onTopicChange(topic)
        search()
    }

    fun search() {
        val current = state.value
        if (!current.canSearch) return
        val topic = current.topic.trim()
        searchJob = viewModelScope.launch {
            state.update {
                it.copy(
                    step = DiscoveryStep.SEARCHING,
                    hasSearched = true,
                    resources = emptyList(),
                    selectedUrls = emptySet(),
                    failedSources = emptySet(),
                    aiNotice = null,
                    errorMessage = null
                )
            }
            try {
                val result = discovery.discover(topic, current.sources) { step -> state.update { it.copy(step = step) } }
                state.update {
                    it.copy(
                        step = null,
                        resources = result.resources,
                        selectedUrls = result.resources.filter { r -> r.isRecommended && !r.isSaved }.map { r -> r.candidate.url }.toSet(),
                        failedSources = result.failedSources,
                        aiNotice = result.aiError?.let { e ->
                            e.toLlmUserMessage(AI_FAILED_PREFIX) + "\n検索の結果を、AI のおすすめ無しで表示しています。"
                        },
                        errorMessage = if (result.resources.isEmpty() && result.failedSources.containsAll(current.sources)) {
                            "資料を検索できませんでした。ネットワーク接続を確認してください。"
                        } else {
                            null
                        }
                    )
                }
            } catch (e: CancellationException) {
                state.update { it.copy(step = null) }
                throw e
            } catch (e: Exception) {
                Timber.w(e, "資料を探せなかった")
                state.update { it.copy(step = null, errorMessage = "資料を探せませんでした: ${e.message ?: "不明なエラー"}") }
            }
        }
    }

    /** 探すのをやめる(AI が選んでいる途中でも止める)。 */
    fun cancel() {
        searchJob?.cancel()
    }

    fun toggleSelection(url: String) {
        state.update { current ->
            val resource = current.resources.firstOrNull { it.candidate.url == url }
            if (resource == null || resource.isSaved) {
                current
            } else {
                val selected = if (url in current.selectedUrls) current.selectedUrls - url else current.selectedUrls + url
                current.copy(selectedUrls = selected)
            }
        }
    }

    /** 選んだ資料をブックマークに追加する。追加したものは「保存済み」にし、選択から外す。 */
    fun saveSelected() {
        val current = state.value
        val targets = current.resources.filter { it.candidate.url in current.selectedUrls && !it.isSaved }
        if (targets.isEmpty() || current.isBusy) return
        viewModelScope.launch {
            state.update { it.copy(isSaving = true) }
            val message = try {
                val added = discovery.save(targets.map { it.candidate })
                val savedUrls = targets.map { it.candidate.url }.toSet()
                state.update {
                    it.copy(
                        resources = it.resources.map { r -> if (r.candidate.url in savedUrls) r.copy(isSaved = true) else r },
                        selectedUrls = it.selectedUrls - savedUrls
                    )
                }
                if (added > 0) "${added}件をブックマークに追加しました。AI が本文を読んで要約します" else "すでに保存してありました"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "資料をブックマークに追加できなかった")
                "ブックマークに追加できませんでした"
            }
            state.update { it.copy(isSaving = false, message = message) }
        }
    }

    fun onMessageShown() {
        state.update { it.copy(message = null) }
    }

    private companion object {
        const val AI_FAILED_PREFIX = "AI で資料を選べませんでした"
    }
}
