package com.unchunks.echomark.di

import com.unchunks.echomark.data.repository.AiSetupRepositoryImpl
import com.unchunks.echomark.data.repository.ChatModelRepositoryImpl
import com.unchunks.echomark.data.voice.AndroidChatSpeechOutput
import com.unchunks.echomark.data.voice.MicrophoneChatVoiceInput
import com.unchunks.echomark.domain.repository.AiSetupRepository
import com.unchunks.echomark.domain.repository.ChatModelRepository
import com.unchunks.echomark.domain.repository.ChatSpeechOutput
import com.unchunks.echomark.domain.repository.ChatVoiceInput
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** チャット画面まわりで使うバインディング。 */
@Module
@InstallIn(SingletonComponent::class)
abstract class ChatModule {

    @Binds
    abstract fun bindAiSetupRepository(
        impl: AiSetupRepositoryImpl
    ): AiSetupRepository

    @Binds
    abstract fun bindChatVoiceInput(impl: MicrophoneChatVoiceInput): ChatVoiceInput

    @Binds
    abstract fun bindChatSpeechOutput(impl: AndroidChatSpeechOutput): ChatSpeechOutput

    @Binds
    abstract fun bindChatModelRepository(
        impl: ChatModelRepositoryImpl
    ): ChatModelRepository
}
