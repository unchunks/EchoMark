package com.unchunks.echomark.data.ai

import com.unchunks.echomark.domain.provider.LlmProvider

/**
 * 端末内 AI を、使うモデルを決めて呼ぶ [LlmProvider] を返す(用途ごとに別の端末内モデルを使うため)。
 * 実装(LocalLlmProvider)は、同時に読み込むモデルを1つにする。別のモデルを使う呼び出しが来たら、読み込んでいるモデルを
 * 解放してから読み込む(端末内のモデルは数百MB〜数GBあり、2つ持つとメモリが足りなくなるため)。
 */
fun interface LocalLlmProviders {
    /** [modelId] のモデルで動く [LlmProvider]。null か、見つからない(削除した)なら既定のモデル */
    fun forModel(modelId: String?): LlmProvider
}
