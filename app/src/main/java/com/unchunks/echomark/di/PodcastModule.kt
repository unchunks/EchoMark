package com.unchunks.echomark.di

import android.content.Context
import com.unchunks.components.core.DispatcherProvider
import com.unchunks.echomark.data.podcast.PodcastEpisodeLibrary
import com.unchunks.echomark.data.podcast.PodcastEpisodeStore
import com.unchunks.echomark.data.podcast.PodcastRepositoryImpl
import com.unchunks.echomark.data.podcast.PodcastWorkScheduler
import com.unchunks.echomark.data.podcast.playback.MediaControllerPodcastPlayback
import com.unchunks.echomark.data.podcast.tts.AndroidPodcastSpeechEngine
import com.unchunks.echomark.data.podcast.tts.PodcastSpeechEngine
import com.unchunks.echomark.domain.podcast.PodcastPlayback
import com.unchunks.echomark.domain.podcast.PodcastRepository
import com.unchunks.echomark.worker.WorkManagerPodcastScheduler
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

/** ポッドキャスト(ブックマークから作る音声) */
@Module
@InstallIn(SingletonComponent::class)
abstract class PodcastModule {
    @Binds
    abstract fun bindPodcastRepository(impl: PodcastRepositoryImpl): PodcastRepository

    @Binds
    abstract fun bindPodcastWorkScheduler(impl: WorkManagerPodcastScheduler): PodcastWorkScheduler

    @Binds
    abstract fun bindPodcastSpeechEngine(impl: AndroidPodcastSpeechEngine): PodcastSpeechEngine

    @Binds
    abstract fun bindPodcastPlayback(impl: MediaControllerPodcastPlayback): PodcastPlayback

    companion object {
        /** エピソードを置くフォルダ。backup_rules.xml / data_extraction_rules.xml の除外指定と一致させること */
        const val PODCASTS_DIR = "podcasts"

        @Provides
        @Singleton
        fun providePodcastEpisodeLibrary(@ApplicationContext context: Context, dispatcherProvider: DispatcherProvider): PodcastEpisodeLibrary =
            PodcastEpisodeLibrary(PodcastEpisodeStore(File(context.filesDir, PODCASTS_DIR)), dispatcherProvider.io)
    }
}
