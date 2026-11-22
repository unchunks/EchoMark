package com.unchunks.echomark.ui.podcast

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unchunks.components.speechtext.ReadingDictionary
import com.unchunks.components.speechtext.ReadingEntry
import com.unchunks.components.speechtext.SpeechNormalizer
import com.unchunks.echomark.domain.podcast.AppReadings
import com.unchunks.echomark.domain.repository.AppSettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * 読みの辞書の画面の状態。
 * @property sample 「読みを確かめる」に入れた文
 * @property samplePreview [sample] を読み上げ用に整えた文(辞書を当てたもの)。空なら空
 */
data class ReadingDictionaryUiState(
    val isLoading: Boolean = true,
    val entries: List<ReadingEntry> = emptyList(),
    val sample: String = "",
    val samplePreview: String = "",
    val errorMessage: String? = null
)

/** 読みの辞書の画面で一度だけ伝える出来事(Snackbar で知らせる)。 */
sealed interface ReadingDictionaryMessage {
    data class Saved(val surface: String) : ReadingDictionaryMessage
    data class Removed(val entry: ReadingEntry) : ReadingDictionaryMessage
    data class Failed(val message: String) : ReadingDictionaryMessage
}

@HiltViewModel
class ReadingDictionaryViewModel @Inject constructor(
    private val appSettings: AppSettingsRepository
) : ViewModel() {

    private val messageChannel = Channel<ReadingDictionaryMessage>(Channel.BUFFERED)
    val messages: Flow<ReadingDictionaryMessage> = messageChannel.receiveAsFlow()

    private val sample = MutableStateFlow("")

    val uiState: StateFlow<ReadingDictionaryUiState> = combine(appSettings.readingDictionary, sample) { entries, text ->
        ReadingDictionaryUiState(
            isLoading = false,
            entries = entries,
            sample = text,
            samplePreview = if (text.isBlank()) "" else SpeechNormalizer.normalize(text, entries, builtIn = AppReadings.builtIn).plain
        )
    }
        .catch { e ->
            if (e is CancellationException) throw e
            Timber.w(e, "読みの辞書を読み込めなかった")
            emit(ReadingDictionaryUiState(isLoading = false, errorMessage = "読みの辞書を読み込めませんでした。"))
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReadingDictionaryUiState())

    /**
     * 1件を入れる(同じ表記があれば読みを置き換える)。[original] は直す前の表記(表記を変えたときに古いものを消す)。
     * 誤りは画面でも確かめているが、ここでも確かめて知らせる。
     */
    fun save(surface: String, reading: String, original: String? = null) {
        val problem = ReadingEntry.problemOf(surface, reading)
        if (problem != null) {
            send(ReadingDictionaryMessage.Failed(problem))
            return
        }
        update(failure = "読みを保存できませんでした") {
            appSettings.updateReadingDictionary { current ->
                val base = if (original != null) ReadingDictionary.remove(current, original) else current
                ReadingDictionary.upsert(base, ReadingEntry(surface, reading))
            }
            ReadingDictionaryMessage.Saved(ReadingEntry.normalize(surface))
        }
    }

    fun remove(entry: ReadingEntry) {
        update(failure = "読みを削除できませんでした") {
            appSettings.updateReadingDictionary { ReadingDictionary.remove(it, entry.surface) }
            ReadingDictionaryMessage.Removed(entry)
        }
    }

    /** 削除を取り消す(Snackbar の「元に戻す」) */
    fun restore(entry: ReadingEntry) {
        update(failure = "読みを戻せませんでした") {
            appSettings.updateReadingDictionary { ReadingDictionary.upsert(it, entry) }
            ReadingDictionaryMessage.Saved(entry.surface)
        }
    }

    fun setSample(text: String) {
        sample.value = text.take(MAX_SAMPLE_CHARS)
    }

    private fun update(failure: String, block: suspend () -> ReadingDictionaryMessage) {
        viewModelScope.launch {
            val message = try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, failure)
                ReadingDictionaryMessage.Failed(failure)
            }
            messageChannel.send(message)
        }
    }

    private fun send(message: ReadingDictionaryMessage) {
        viewModelScope.launch { messageChannel.send(message) }
    }

    private companion object {
        const val MAX_SAMPLE_CHARS = 500
    }
}
