package com.unchunks.echomark.data.ai.local

/**
 * 入力が文脈長(エンジンの maxTokens)を超えたときの MediaPipe の失敗か。プロンプトは文字数でトークン数を見積もっているため、
 * 1 文字が複数のトークンになる文字(絵文字・まれな漢字など)が多いと超えることがある。
 * 例: 「Input is too long for the model to process: current_step(0) + input_size(4210) was not less than maxTokens(4096).」
 */
internal fun isInputTooLong(e: Throwable): Boolean =
    generateSequence(e) { it.cause }.take(MAX_CAUSE_DEPTH).any { it.message?.contains("Input is too long", ignoreCase = true) == true }

private const val MAX_CAUSE_DEPTH = 5
