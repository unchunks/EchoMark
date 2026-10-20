package com.unchunks.echomark.di

import com.unchunks.echomark.data.extract.DefaultContentExtractor
import com.unchunks.echomark.data.extract.vision.ImageLabelerEngine
import com.unchunks.echomark.data.extract.vision.MlKitImageLabelerEngine
import com.unchunks.echomark.data.extract.vision.MlKitTextRecognizerEngine
import com.unchunks.echomark.data.extract.vision.TextRecognizerEngine
import com.unchunks.echomark.domain.extract.ContentExtractor
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

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
}
