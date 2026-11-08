package com.unchunks.echomark.ui.discover

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

/** 「資料を探す」画面。Web の一覧ページか端末内のファイルから、AI が選んで保存する。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResourceFinderScreen(
    onBack: () -> Unit,
    viewModel: ResourceFinderViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) viewModel.addFiles(uris)
    }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text("資料を探す", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                },
                scrollBehavior = scrollBehavior
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "AI が、下の資料の中から調べたいことに役立つものを選んで保存します。選ぶのは端末内の AI です。",
                style = MaterialTheme.typography.bodyMedium
            )
            OutlinedTextField(
                value = uiState.topic,
                onValueChange = viewModel::onTopicChange,
                label = { Text("調べたいこと") },
                enabled = !uiState.isRunning,
                modifier = Modifier.fillMaxWidth()
            )

            HorizontalDivider()
            Text("Web の一覧ページから探す", style = MaterialTheme.typography.titleSmall)
            Text(
                "検索結果・一覧・まとめのページの URL を入れると、そのページのリンクから選びます。",
                style = MaterialTheme.typography.bodySmall
            )
            OutlinedTextField(
                value = uiState.listUrl,
                onValueChange = viewModel::onListUrlChange,
                label = { Text("一覧ページの URL") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                enabled = !uiState.isRunning,
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = viewModel::searchWeb,
                enabled = !uiState.isRunning,
                modifier = Modifier.fillMaxWidth()
            ) { Text("一覧ページから探す") }

            HorizontalDivider()
            Text("端末内のファイルから探す", style = MaterialTheme.typography.titleSmall)
            OutlinedButton(
                onClick = { pickFiles.launch(arrayOf("*/*")) },
                enabled = !uiState.isRunning,
                modifier = Modifier.fillMaxWidth()
            ) { Text("ファイルを選ぶ") }
            uiState.files.forEach { file ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        file.title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        onClick = { viewModel.removeFile(file.ref) },
                        enabled = !uiState.isRunning
                    ) { Text("外す") }
                }
            }
            Button(
                onClick = viewModel::searchFiles,
                enabled = !uiState.isRunning && uiState.files.isNotEmpty(),
                modifier = Modifier.fillMaxWidth()
            ) { Text("ファイルから探す") }

            HorizontalDivider()
            if (uiState.isRunning) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.padding(4.dp))
                    Text("探しています…", style = MaterialTheme.typography.bodyMedium)
                }
            }
            uiState.message?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }
            uiState.saved?.let { saved ->
                if (saved.isEmpty()) {
                    Text("役立つ資料は見つかりませんでした。", style = MaterialTheme.typography.bodyMedium)
                } else {
                    Text("${saved.size} 件を保存しました", style = MaterialTheme.typography.titleSmall)
                    saved.forEach { Text("・${it.title}", style = MaterialTheme.typography.bodyMedium) }
                }
            }
        }
    }
}
