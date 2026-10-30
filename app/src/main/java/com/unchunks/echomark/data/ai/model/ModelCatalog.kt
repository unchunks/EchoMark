package com.unchunks.echomark.data.ai.model

import com.unchunks.components.llm.ondevice.EngineBackend

/**
 * モデルの公開ページに載っている、ある端末で測った性能。この端末の値ではない(目安)。
 * @property device 測った端末(公開ページの表記)
 * @property decodeTokensPerSecond 出力の速さ
 * @property memoryMegabytes 使ったメモリ。公開ページに無ければ null
 */
data class PublishedModelPerformance(
    val device: String,
    val backend: EngineBackend,
    val decodeTokensPerSecond: Double,
    val memoryMegabytes: Int?
)

/**
 * おすすめの端末内モデル。ファイルは [revision] のコミットに固定し、大きさと SHA-256 は公開ページの値を持つ
 * (リポジトリが更新されても、同じファイルが届き、ダウンロードの後に確かめられる)。
 * @property repo Hugging Face のリポジトリ
 * @property revision ファイルを固定するコミット
 * @property fileName リポジトリの中のファイル名。取り込むと、これがモデルの名前になる
 * @property sizeBytes ファイルの大きさ
 * @property sha256 ファイルの SHA-256(小文字の 16 進)
 * @property license ライセンスの名前
 * @property requiresAgreement 入手に Hugging Face へのログインと利用規約への同意が要るか。要るならアプリから直接は取得できない
 * @property suitedFor 向いている使い方の説明
 * @property published 公開ページに載っている性能。無ければ null
 */
data class ModelCatalogEntry(
    val id: String,
    val name: String,
    val repo: String,
    val revision: String,
    val fileName: String,
    val sizeBytes: Long,
    val sha256: String,
    val license: String,
    val requiresAgreement: Boolean = false,
    val suitedFor: String,
    val published: PublishedModelPerformance? = null
) {
    /** ファイルの形式(拡張子) */
    val format: String get() = fileName.substringAfterLast('.', "")

    /** 公開ページ(モデルカード) */
    val pageUrl: String get() = "$HUGGING_FACE/$repo"

    /** コミットに固定したファイルの URL */
    val downloadUrl: String get() = "$HUGGING_FACE/$repo/resolve/$revision/$fileName"

    private companion object {
        const val HUGGING_FACE = "https://huggingface.co"
    }
}

/**
 * アプリがおすすめする端末内モデルの一覧。文脈長 4096 トークンで動かすため(LocalLlmProvider の MAX_TOKENS)、
 * それより短い文脈に固定して変換したファイルや、特定の NPU 向けのファイルは載せない。
 *
 * 出どころ: Hugging Face の litert-community の各リポジトリ(API の `/api/models/<repo>?blobs=true` でファイル名・大きさ・
 * SHA-256・コミット・公開の条件、モデルカードで性能を確かめた)。確かめた日は [CHECKED_ON]。
 * 値を変えるときは同じ出どころで確かめ直し、[CHECKED_ON] を更新する。推測で書かない。
 */
object ModelCatalog {
    const val CHECKED_ON = "2026-10-30"

    val entries: List<ModelCatalogEntry> = listOf(
        ModelCatalogEntry(
            id = "qwen3-0.6b-int4",
            name = "Qwen3 0.6B(INT4)",
            repo = "litert-community/Qwen3-0.6B",
            revision = "e6bb3e4a1eb3f663153f0c1c12b735d64be657a7",
            fileName = "Qwen3-0.6B_dynamic_wi4b32_afp32.litertlm",
            sizeBytes = 344_671_744,
            sha256 = "03e7da1eb1108b50dffaa9bb52cc7bcbad2eb0c66ca990267f480c1e545d2856",
            license = "Apache-2.0",
            suitedFor = "いちばん小さいモデルです。保存容量やメモリの少ない端末で、タグ付けや短い要約に使うのに向きます",
            published = PublishedModelPerformance("Pixel 8a", EngineBackend.GPU, 35.0, memoryMegabytes = null)
        ),
        ModelCatalogEntry(
            id = "qwen3-0.6b-int8",
            name = "Qwen3 0.6B(INT8)",
            repo = "litert-community/Qwen3-0.6B",
            revision = "e6bb3e4a1eb3f663153f0c1c12b735d64be657a7",
            fileName = "Qwen3-0.6B.litertlm",
            sizeBytes = 613_722_264,
            sha256 = "c5f39b4191926786c3e6668b62af4d6eb15248dfc7a5348252a9b406f33f2108",
            license = "Apache-2.0",
            suitedFor = "同じモデルの INT8 版です。INT4 版より大きくなりますが、CPU で動かす端末の速さの公開値があります",
            published = PublishedModelPerformance("Samsung SM-S937U1", EngineBackend.CPU, 13.02, memoryMegabytes = 2697)
        ),
        ModelCatalogEntry(
            id = "gemma3-1b-int4",
            name = "Gemma 3 1B(INT4)",
            repo = "litert-community/Gemma3-1B-IT",
            revision = "d448835d84411a08f577e25f3afebe528402d73b",
            fileName = "gemma3-1b-it-int4.litertlm",
            sizeBytes = 584_417_280,
            sha256 = "1325ae366d31950f137c9c357b9fa89448b176d76998180c08ceaca78bba98be",
            license = "Gemma Terms of Use",
            requiresAgreement = true,
            suitedFor = "Google の小さいモデルです。1 GB 未満で、要約やチャットを試すのに向きます"
        ),
        ModelCatalogEntry(
            id = "qwen3.5-0.8b-int8",
            name = "Qwen3.5 0.8B(INT8)",
            repo = "litert-community/Qwen3.5-0.8B",
            revision = "0cd9e7a616d5f0d5c5cfab471dfc02b9ab1d2c17",
            fileName = "Qwen3.5-0.8B_int8.litertlm",
            sizeBytes = 964_566_992,
            sha256 = "25447e2550082aed6145bbc755ec4da5d535f98f6a0c386865fdae2ccb88aed1",
            license = "Apache-2.0",
            suitedFor = "1 GB 前後の新しいモデルです。メモリに余裕のある端末で、要約とチャットに使うのに向きます",
            published = PublishedModelPerformance("Galaxy S26", EngineBackend.CPU, 44.49, memoryMegabytes = 2043)
        ),
        ModelCatalogEntry(
            id = "gemma-4-e2b",
            name = "Gemma 4 E2B",
            repo = "litert-community/gemma-4-E2B-it-litert-lm",
            revision = "1caa527c1e88b55f046fcd8d2b4265606680663d",
            fileName = "gemma-4-E2B-it.litertlm",
            sizeBytes = 2_588_147_712,
            sha256 = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c",
            license = "Apache-2.0",
            suitedFor = "Google の新しいモデルです。保存容量とメモリに余裕のある端末で、より良い要約とチャットに向きます",
            published = PublishedModelPerformance("Galaxy S26 Ultra", EngineBackend.CPU, 46.9, memoryMegabytes = 1733)
        ),
        ModelCatalogEntry(
            id = "gemma-4-e4b",
            name = "Gemma 4 E4B",
            repo = "litert-community/gemma-4-E4B-it-litert-lm",
            revision = "e6a3892a8c22cf87ff99583996149cf3cb32452b",
            fileName = "gemma-4-E4B-it.litertlm",
            sizeBytes = 3_659_530_240,
            sha256 = "0b2a8980ce155fd97673d8e820b4d29d9c7d99b8fa6806f425d969b145bd52e0",
            license = "Apache-2.0",
            suitedFor = "この一覧でいちばん大きいモデルです。メモリの多い高性能な端末で、品質を優先するときに向きます",
            published = PublishedModelPerformance("Galaxy S26 Ultra", EngineBackend.CPU, 17.7, memoryMegabytes = 3283)
        )
    )
}
