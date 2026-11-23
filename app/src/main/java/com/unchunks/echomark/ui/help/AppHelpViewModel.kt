package com.unchunks.echomark.ui.help

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unchunks.components.llm.ChatRole
import com.unchunks.echomark.domain.help.AppAction
import com.unchunks.echomark.domain.help.AppActionCatalog
import com.unchunks.echomark.domain.help.AppActionRunner
import com.unchunks.echomark.domain.help.AppGuideRetriever
import com.unchunks.echomark.domain.help.AppHelpAssistant
import com.unchunks.echomark.domain.model.ChatMessage
import com.unchunks.echomark.domain.provider.toLlmUserMessage
import com.unchunks.echomark.ui.chat.needsAiSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * 1つのやり取り。
 * @property sourceTitles 回答の根拠に渡した使い方の案内の題名(回答だけ。どこを見て答えたかを示す)
 * @property actions 回答の後に出す操作のボタン(押すと実行する)
 * @property isResult 操作を実行した結果の知らせ(AI の回答ではない。会話の履歴に入れない)
 */
data class AppHelpMessage(
    val id: Long,
    val fromUser: Boolean,
    val text: String,
    val sourceTitles: List<String> = emptyList(),
    val actions: List<AppAction> = emptyList(),
    val isResult: Boolean = false
)

/**
 * 回答・操作ができなかったこと。
 * @property needsAiSettings AI の設定を見直せば直る(モデル未取り込みなど)。「AI の設定を開く」を出す
 */
data class AppHelpError(val message: String, val needsAiSettings: Boolean)

/**
 * 使い方を AI に聞く画面の状態。
 * @property answering 回答を作っている間 true(送信を止め、「止める」を出す)
 * @property runningAction 操作を実行している間 true(操作のボタンを押せなくする)
 */
data class AppHelpUiState(
    val messages: List<AppHelpMessage> = emptyList(),
    val input: String = "",
    val answering: Boolean = false,
    val runningAction: Boolean = false,
    val error: AppHelpError? = null,
    val suggestions: List<String> = SUGGESTED_QUESTIONS
) {
    val canSend: Boolean get() = input.isNotBlank() && !answering

    companion object {
        /** 最初に出す質問の例。よく聞かれそうなことと、頼める操作の例 */
        val SUGGESTED_QUESTIONS = listOf(
            "記事を保存するには?",
            "要約が作られないときは?",
            "ダークモードにして",
            "機種変更でデータを移すには?",
            "AI のモデルはどこで入れる?"
        )
    }
}

/**
 * アプリの使い方を端末内 AI に聞き、頼んだ操作を行う。会話は画面を閉じると消える(保存しない)。
 * 質問ごとに、使い方の案内から近い節を選んで AI に渡す([AppGuideRetriever])。
 * AI が提案した操作はボタンにして、利用者が押したときだけ実行する([AppActionCatalog])。
 */
@HiltViewModel
class AppHelpViewModel @Inject constructor(
    private val assistant: AppHelpAssistant,
    private val runner: AppActionRunner
) : ViewModel() {

    private val _uiState = MutableStateFlow(AppHelpUiState())
    val uiState: StateFlow<AppHelpUiState> = _uiState.asStateFlow()

    private val _navigation = Channel<AppAction>(Channel.BUFFERED)

    /** 画面を開く操作([AppAction.Open]・[AppAction.Search])。画面側で遷移する */
    val navigation: Flow<AppAction> = _navigation.receiveAsFlow()

    private var answerJob: Job? = null
    private var nextId = 1L

    fun onInputChange(text: String) = _uiState.update { it.copy(input = text) }

    /** 入力欄の質問を送る */
    fun send() {
        val question = _uiState.value.input.trim()
        if (question.isEmpty() || _uiState.value.answering) return
        ask(question)
    }

    /** 質問の例を押したとき。入力欄を使わずにそのまま送る */
    fun askSuggestion(question: String) {
        if (_uiState.value.answering) return
        ask(question)
    }

    /** 回答の生成を止める。そこまでの回答は残す(空なら消す) */
    fun stop() {
        answerJob?.cancel()
    }

    fun dismissError() = _uiState.update { it.copy(error = null) }

    /**
     * [messageId] の回答に出した操作のボタンを押した。画面を開く操作は画面側へ渡し、それ以外は実行して結果を会話に足す。
     * 実行した操作のボタンは消す(同じ保存・切り替えを二度しないように)。画面を開くボタンは残す(戻ってきてまた開けるように)。
     */
    fun runAction(messageId: Long, action: AppAction) {
        if (action is AppAction.Open || action is AppAction.Search) {
            _navigation.trySend(action)
            return
        }
        if (_uiState.value.runningAction) return
        _uiState.update { it.copy(runningAction = true, error = null) }
        viewModelScope.launch {
            try {
                val result = runner.run(action)
                _uiState.update { state ->
                    state.copy(
                        messages = state.messages.map {
                            if (it.id == messageId) it.copy(actions = it.actions - action) else it
                        } + AppHelpMessage(nextId++, fromUser = false, text = result, isResult = true)
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "使い方の AI から頼まれた操作に失敗: %s", action)
                _uiState.update {
                    it.copy(error = AppHelpError("操作できませんでした(${action.label})。もう一度お試しください", needsAiSettings = false))
                }
            } finally {
                _uiState.update { it.copy(runningAction = false) }
            }
        }
    }

    private fun ask(question: String) {
        // 今回の質問より前のやり取りだけを履歴にする(操作の結果の知らせ・空の回答は渡さない)
        val history = _uiState.value.messages
            .filter { it.text.isNotBlank() && !it.isResult }
            .map { it.toChatMessage() }
        val sections = AppGuideRetriever.retrieve(question)
        val candidates = AppActionCatalog.candidatesFor(sections)
        val questionMessage = AppHelpMessage(nextId++, fromUser = true, text = question)
        val answerId = nextId++
        _uiState.update {
            it.copy(
                messages = it.messages + questionMessage +
                    AppHelpMessage(answerId, fromUser = false, text = "", sourceTitles = sections.map { s -> s.title }),
                input = "",
                answering = true,
                error = null
            )
        }
        // 操作の行を含む、AI の出力そのもの。画面には操作の行を除いて出す
        var raw = ""
        answerJob = viewModelScope.launch {
            try {
                assistant.answer(question, sections, candidates, history).collect { chunk ->
                    raw += chunk
                    updateAnswer(answerId) { it.copy(text = AppActionCatalog.visibleText(raw, streaming = true)) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "使い方の質問に答えられなかった")
                _uiState.update {
                    it.copy(error = AppHelpError(e.toLlmUserMessage(ANSWER_FAILED_PREFIX), e.needsAiSettings()))
                }
            } finally {
                // 止めた・失敗したときも、質問の URL や言葉から決まる操作は出す(AI が使えなくても保存や画面を開くのはできる)
                val actions = AppActionCatalog.suggest(question, raw, candidates, sections)
                _uiState.update { state ->
                    state.copy(
                        messages = state.messages.mapNotNull { message ->
                            if (message.id != answerId) return@mapNotNull message
                            val finished = message.copy(text = AppActionCatalog.visibleText(raw), actions = actions)
                            // 回答も操作も無ければ、空の吹き出しを残さない
                            finished.takeIf { it.text.isNotBlank() || it.actions.isNotEmpty() }
                        },
                        answering = false
                    )
                }
            }
        }
    }

    private fun updateAnswer(id: Long, transform: (AppHelpMessage) -> AppHelpMessage) = _uiState.update { state ->
        state.copy(messages = state.messages.map { if (it.id == id) transform(it) else it })
    }

    private fun AppHelpMessage.toChatMessage() = ChatMessage(
        conversationId = 0,
        role = if (fromUser) ChatRole.USER else ChatRole.ASSISTANT,
        content = text,
        createdAt = 0
    )

    private companion object {
        const val ANSWER_FAILED_PREFIX = "回答を作れませんでした"
    }
}
