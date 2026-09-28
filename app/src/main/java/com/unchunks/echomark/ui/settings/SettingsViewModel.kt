package com.unchunks.echomark.ui.settings

import androidx.lifecycle.ViewModel
import com.unchunks.echomark.data.ai.model.ModelManager
import com.unchunks.echomark.data.ai.model.ModelSpec
import com.unchunks.echomark.data.ai.model.ModelSpecs
import com.unchunks.echomark.data.ai.model.ModelState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/** 設定画面に表示するモデル1件分。 */
data class ModelItemUiState(
    val spec: ModelSpec,
    val state: ModelState
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val modelManager: ModelManager
) : ViewModel() {

    val models: Flow<List<ModelItemUiState>> = modelManager.states.map { states ->
        ModelSpecs.all.map { spec ->
            ModelItemUiState(spec, states[spec.id] ?: ModelState.NotDownloaded)
        }
    }

    fun download(spec: ModelSpec) = modelManager.startDownload(spec)

    fun cancel(spec: ModelSpec) = modelManager.cancelDownload(spec)

    fun delete(spec: ModelSpec) = modelManager.delete(spec)
}
