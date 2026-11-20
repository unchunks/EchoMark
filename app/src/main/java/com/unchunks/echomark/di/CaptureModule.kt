package com.unchunks.echomark.di

import com.unchunks.echomark.data.capture.CaptureFileStore
import com.unchunks.echomark.data.voice.MediaVoiceMemoRecorder
import com.unchunks.echomark.domain.capture.CaptureFiles
import com.unchunks.echomark.domain.capture.VoiceMemoRecorder
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** 写真・動画の撮影と音声メモの録音で使うバインディング。 */
@Module
@InstallIn(SingletonComponent::class)
abstract class CaptureModule {

    @Binds
    @Singleton
    abstract fun bindCaptureFiles(impl: CaptureFileStore): CaptureFiles

    /** 録音の状態を持つので、画面(ViewModel)ごとに別の実体を使う */
    @Binds
    abstract fun bindVoiceMemoRecorder(impl: MediaVoiceMemoRecorder): VoiceMemoRecorder
}
