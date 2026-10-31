package com.unchunks.echomark.ui.capture

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.unchunks.echomark.ui.common.formatDuration

/**
 * 追加シートの「撮る・録る」の部分の表示内容。
 * @property canTakePhoto 写真を撮るカメラのアプリがある
 * @property canTakeVideo 動画を撮るカメラのアプリがある
 * @property canRecordVoice マイクがある
 * @property message 撮れなかった・録れなかった理由
 */
data class CaptureUiState(
    val canTakePhoto: Boolean = false,
    val canTakeVideo: Boolean = false,
    val canRecordVoice: Boolean = false,
    val voiceMemo: VoiceMemoState = VoiceMemoState.Idle,
    val message: String? = null
) {
    val isRecording: Boolean get() = voiceMemo is VoiceMemoState.Recording
    val hasAnyAction: Boolean get() = canTakePhoto || canTakeVideo || canRecordVoice
}

/**
 * 写真・動画を撮る、音声メモを録るボタン。録音中は録音の表示に替わる。
 * 通信せず、撮ったものはその場でファイルの一覧に加わる。
 */
@Composable
fun CaptureSection(
    state: CaptureUiState,
    onTakePhoto: () -> Unit,
    onTakeVideo: () -> Unit,
    onStartVoiceMemo: () -> Unit,
    onStopVoiceMemo: () -> Unit,
    onCancelVoiceMemo: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (!state.hasAnyAction && !state.isRecording && state.message == null) return
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (val memo = state.voiceMemo) {
            is VoiceMemoState.Recording -> RecordingPanel(memo, onStopVoiceMemo, onCancelVoiceMemo)
            VoiceMemoState.Idle -> if (state.hasAnyAction) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.canTakePhoto) CaptureButton("写真", Icons.Outlined.PhotoCamera, onTakePhoto, Modifier.weight(1f))
                    if (state.canTakeVideo) CaptureButton("動画", Icons.Outlined.Videocam, onTakeVideo, Modifier.weight(1f))
                    if (state.canRecordVoice) CaptureButton("録音", Icons.Outlined.Mic, onStartVoiceMemo, Modifier.weight(1f))
                }
                Text(
                    text = "その場で撮影・録音して保存できます。圏外でも使えます",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        state.message?.let {
            Text(text = it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun CaptureButton(label: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier) {
    OutlinedButton(onClick = onClick, modifier = modifier, contentPadding = ButtonDefaults.ButtonWithIconContentPadding) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
        Spacer(Modifier.size(ButtonDefaults.IconSpacing))
        Text(label, maxLines = 1)
    }
}

@Composable
private fun RecordingPanel(state: VoiceMemoState.Recording, onStop: () -> Unit, onCancel: () -> Unit) {
    val elapsed = formatDuration(state.elapsedMillis)
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = "録音中 $elapsed" }
            ) {
                Box(Modifier.size(10.dp).background(MaterialTheme.colorScheme.error, CircleShape))
                Text(text = "録音中", style = MaterialTheme.typography.titleSmall)
                Text(text = elapsed, style = MaterialTheme.typography.titleSmall)
            }
            // 音の大きさ。マイクに声が届いているかの目安
            LinearProgressIndicator(
                progress = { state.level },
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.error,
                trackColor = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.15f)
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onCancel) { Text("破棄") }
                Spacer(Modifier.size(8.dp))
                Button(onClick = onStop) { Text("止めて追加") }
            }
        }
    }
}
