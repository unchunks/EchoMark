package com.unchunks.echomark.di

import android.content.Context
import com.unchunks.components.llm.ondevice.AndroidDeviceSpecsReader
import com.unchunks.components.llm.ondevice.DeviceSpecsReader
import com.unchunks.echomark.data.ai.LlmSummaryTranslator
import com.unchunks.echomark.data.ai.LocalLlmProviders
import com.unchunks.echomark.data.ai.LocalModelBenchmarker
import com.unchunks.echomark.data.ai.api.AndroidAttachmentLoader
import com.unchunks.echomark.data.ai.api.ApiLlmProvider
import com.unchunks.echomark.data.ai.api.ApiLlmProviderFactory
import com.unchunks.echomark.data.ai.api.AttachmentLoader
import com.unchunks.echomark.data.ai.local.LocalLlmProvider
import com.unchunks.echomark.data.ai.model.AndroidModelDownloadClient
import com.unchunks.echomark.data.ai.model.DownloadedModelInstaller
import com.unchunks.echomark.data.ai.model.ModelDownloadClient
import com.unchunks.echomark.data.ai.model.ModelManager
import com.unchunks.echomark.data.help.AppActionRunnerImpl
import com.unchunks.echomark.data.help.LocalAppHelpAssistant
import com.unchunks.echomark.domain.help.AppActionRunner
import com.unchunks.echomark.domain.help.AppHelpAssistant
import com.unchunks.echomark.domain.provider.SummaryTranslator
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class AiProviderModule {

    /** 端末内 AI。用途ごとに選んだ端末内モデルで動く LlmProvider を返す */
    @Binds
    abstract fun bindLocalLlmProviders(impl: LocalLlmProvider): LocalLlmProviders

    /** 取り込んだ端末内モデルをこの端末で測る */
    @Binds
    abstract fun bindLocalModelBenchmarker(impl: LocalLlmProvider): LocalModelBenchmarker

    @Binds
    abstract fun bindApiLlmProviderFactory(impl: ApiLlmProvider): ApiLlmProviderFactory

    /** おすすめのモデルのダウンロードを端末の DownloadManager に任せる */
    @Binds
    abstract fun bindModelDownloadClient(impl: AndroidModelDownloadClient): ModelDownloadClient

    /** ダウンロードしたモデルを一覧に加える */
    @Binds
    abstract fun bindDownloadedModelInstaller(impl: ModelManager): DownloadedModelInstaller

    @Binds
    abstract fun bindAttachmentLoader(impl: AndroidAttachmentLoader): AttachmentLoader

    /** アプリの使い方の質問に端末内 AI で答える */
    @Binds
    abstract fun bindAppHelpAssistant(impl: LocalAppHelpAssistant): AppHelpAssistant

    /** 使い方の AI から頼まれた操作(保存・設定の切り替えなど)を実行する */
    @Binds
    abstract fun bindAppActionRunner(impl: AppActionRunnerImpl): AppActionRunner

    /** 要約の翻訳(#24) */
    @Binds
    abstract fun bindSummaryTranslator(impl: LlmSummaryTranslator): SummaryTranslator

    companion object {
        /** この端末の性能を読む(部品の llm-ondevice) */
        @Provides
        fun provideDeviceSpecsReader(@ApplicationContext context: Context): DeviceSpecsReader = AndroidDeviceSpecsReader(context)
    }
}
