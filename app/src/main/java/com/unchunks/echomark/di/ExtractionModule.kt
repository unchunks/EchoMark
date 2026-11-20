package com.unchunks.echomark.di

import android.content.Context
import com.unchunks.components.documenttext.ImageLabelerEngine
import com.unchunks.components.documenttext.MlKitImageLabelerEngine
import com.unchunks.components.documenttext.MlKitTextRecognizerEngine
import com.unchunks.components.documenttext.TextRecognizerEngine
import com.unchunks.components.speech.OnDeviceSpeechTranscriber
import com.unchunks.echomark.data.extract.DefaultContentExtractor
import com.unchunks.echomark.domain.extract.ContentExtractor
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** ファイルの中身の取り出し(OCR・PDF・文字起こし)。 */
@Module
@InstallIn(SingletonComponent::class)
abstract class ExtractionModule {

    @Binds
    abstract fun bindContentExtractor(impl: DefaultContentExtractor): ContentExtractor

    @Binds
    abstract fun bindTextRecognizerEngine(impl: MlKitTextRecognizerEngine): TextRecognizerEngine

    @Binds
    abstract fun bindImageLabelerEngine(impl: MlKitImageLabelerEngine): ImageLabelerEngine

    companion object {
        @Provides
        @Singleton
        fun provideOnDeviceSpeechTranscriber(@ApplicationContext context: Context): OnDeviceSpeechTranscriber =
            OnDeviceSpeechTranscriber(context)
    }
}
