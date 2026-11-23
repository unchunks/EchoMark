package com.unchunks.echomark.ui.help

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unchunks.components.llm.ChatRole
import com.unchunks.echomark.domain.help.AppGuideRetriever
import com.unchunks.echomark.domain.help.AppHelpAssistant
import com.unchunks.echomark.domain.model.ChatMessage
import com.unchunks.echomark.domain.provider.toLlmUserMessage
import com.unchunks.echomark.ui.chat.needsAiSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * 1つのやり取り。
 * @property sourceTitles 回答の根拠に渡した使い方の案内の題名(回答だけ。どこを見て答えたかを示す)
 */
data class AppHelpMessage(
    val id: Long,
    val fromUser: Boolean,
    val text: String,
    val sourceTitles: List<String> = emptyList()
)

/**
 * 回答できなかったこと。
 * @property needsAiSettings AI の設定を見直せば直る(モデル未取り込みなど)。「AI の設定を開く」を出す
 */
data class AppHelpError(val message: String, val needsAiSettings: Boolean)

/**
 * 使い方を AI に聞く画面の状態。
 * @property answering 回答を作っている間 true(送信を止め、「止める」を出す)
 */
data class AppHelpUiState(
    val messages: List<AppHelpMessage> = emptyList(),
    val input: String = "",
    val answering: Boolean = false,
    val error: AppHelpError? = null,
    val suggestions: List<String> = SUGGESTED_QUESTIONS
) {
    val canSend: Boolean get() = input.isNotBlank() && !answering

    companion object {
        /** 最初に出す質問の例。よく聞かれそうなこと */
        val SUGGESTED_QUESTIONS = listOf(
            "記事を保存するには?",
            "要約が作られないときは?",
            "機種変更でデータを移すには?",
            "AI のモデルはどこで入れる?"
        )
    }
}

/**
 * アプリの使い方を端末内 AI に聞く。会話は画面を閉じると消える(保存しない。使い方の質問は残す必要が薄いため)。
 * 質問ごとに、使い方の案内から近い節を選んで AI に渡す([AppGuideRetriever])。
 */
@HiltViewModel
class AppHelpViewModel @Inject constructor(
    private val assistant: AppHelpAssistant
) : ViewModel() {

    private val _uiState = MutableStateFlow(AppHelpUiState())
    val uiState: StateFlow<AppHelpUiState> = _uiState.asStateFlow()

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

    private fun ask(question: String) {
        // 今回の質問より前のやり取りだけを履歴にする(失敗して空の回答は渡さない)
        val history = _uiState.value.messages
            .filter { it.text.isNotBlank() }
            .map { it.toChatMessage() }
        val sections = AppGuideRetriever.retrieve(question)
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
        answerJob = viewModelScope.launch {
            try {
                assistant.answer(question, sections, history).collect { chunk ->
                    updateAnswer(answerId) { it + chunk }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "使い方の質問に答えられなかった")
                _uiState.update {
                    it.copy(error = AppHelpError(e.toLlmUserMessage(ANSWER_FAILED_PREFIX), e.needsAiSettings()))
                }
            } finally {
                // 回答が空のまま終わった(失敗・止めた)ときは、空の吹き出しを残さない
                _uiState.update { state ->
                    state.copy(
                        messages = state.messages.filterNot { it.id == answerId && it.text.isBlank() },
                        answering = false
                    )
                }
            }
        }
    }

    private fun updateAnswer(id: Long, transform: (String) -> String) = _uiState.update { state ->
        state.copy(messages = state.messages.map { if (it.id == id) it.copy(text = transform(it.text)) else it })
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
