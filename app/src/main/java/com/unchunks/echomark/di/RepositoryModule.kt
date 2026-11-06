package com.unchunks.echomark.di

import com.unchunks.components.securestorage.KeystoreSecretCipher
import com.unchunks.components.securestorage.SecretCipher
import com.unchunks.echomark.data.attachment.AttachmentStore
import com.unchunks.echomark.data.backup.DataManagementRepositoryImpl
import com.unchunks.echomark.data.repository.ApiKeyRepositoryImpl
import com.unchunks.echomark.data.repository.AppSettingsRepositoryImpl
import com.unchunks.echomark.data.repository.BookmarkRepositoryImpl
import com.unchunks.echomark.data.repository.ChatRepositoryImpl
import com.unchunks.echomark.data.repository.ApiUsageRepositoryImpl
import com.unchunks.echomark.data.repository.ProcessingStatsRepositoryImpl
import com.unchunks.echomark.data.repository.CategoryRepositoryImpl
import com.unchunks.echomark.data.repository.CollectionRepositoryImpl
import com.unchunks.echomark.data.repository.DuplicateRepositoryImpl
import com.unchunks.echomark.data.repository.ExportRepositoryImpl
import com.unchunks.echomark.data.repository.NoteRepositoryImpl
import com.unchunks.echomark.data.repository.PlaceRepositoryImpl
import com.unchunks.echomark.data.place.AndroidCurrentLocationProvider
import com.unchunks.echomark.data.place.ExifPhotoLocationReader
import com.unchunks.echomark.data.place.PhotoLocationReader
import com.unchunks.echomark.domain.repository.CurrentLocationProvider
import com.unchunks.echomark.domain.repository.PlaceRepository
import com.unchunks.echomark.data.repository.SmartFolderRepositoryImpl
import com.unchunks.echomark.data.repository.TagRepositoryImpl
import com.unchunks.echomark.domain.repository.ApiKeyRepository
import com.unchunks.echomark.domain.repository.AppSettingsRepository
import com.unchunks.echomark.domain.repository.AttachmentRepository
import com.unchunks.echomark.data.chat.ChatAttachmentLoader
import com.unchunks.echomark.domain.repository.BookmarkRepository
import com.unchunks.echomark.domain.repository.ChatAttachmentSource
import com.unchunks.echomark.domain.repository.ChatRepository
import com.unchunks.echomark.domain.repository.DataManagementRepository
import com.unchunks.echomark.domain.repository.CollectionRepository
import com.unchunks.echomark.domain.repository.DuplicateRepository
import com.unchunks.echomark.domain.repository.ExportRepository
import com.unchunks.echomark.domain.repository.NoteRepository
import com.unchunks.echomark.domain.repository.ProcessingStatsRepository
import com.unchunks.echomark.domain.usage.ApiUsageRepository
import com.unchunks.echomark.domain.repository.CategoryRepository
import com.unchunks.echomark.domain.repository.SmartFolderRepository
import com.unchunks.echomark.domain.repository.TagRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    abstract fun bindBookmarkRepository(
        impl: BookmarkRepositoryImpl
    ): BookmarkRepository

    @Binds
    abstract fun bindChatRepository(
        impl: ChatRepositoryImpl
    ): ChatRepository

    @Binds
    abstract fun bindAppSettingsRepository(
        impl: AppSettingsRepositoryImpl
    ): AppSettingsRepository

    @Binds
    abstract fun bindApiKeyRepository(
        impl: ApiKeyRepositoryImpl
    ): ApiKeyRepository

    @Binds
    abstract fun bindTagRepository(
        impl: TagRepositoryImpl
    ): TagRepository

    @Binds
    abstract fun bindCategoryRepository(
        impl: CategoryRepositoryImpl
    ): CategoryRepository

    @Binds
    abstract fun bindCollectionRepository(
        impl: CollectionRepositoryImpl
    ): CollectionRepository

    @Binds
    abstract fun bindExportRepository(
        impl: ExportRepositoryImpl
    ): ExportRepository

    @Binds
    abstract fun bindNoteRepository(
        impl: NoteRepositoryImpl
    ): NoteRepository

    @Binds
    abstract fun bindPlaceRepository(
        impl: PlaceRepositoryImpl
    ): PlaceRepository

    @Binds
    abstract fun bindPhotoLocationReader(
        impl: ExifPhotoLocationReader
    ): PhotoLocationReader

    @Binds
    abstract fun bindCurrentLocationProvider(
        impl: AndroidCurrentLocationProvider
    ): CurrentLocationProvider

    @Binds
    abstract fun bindDuplicateRepository(
        impl: DuplicateRepositoryImpl
    ): DuplicateRepository

    @Binds
    abstract fun bindSmartFolderRepository(
        impl: SmartFolderRepositoryImpl
    ): SmartFolderRepository

    @Binds
    abstract fun bindDataManagementRepository(
        impl: DataManagementRepositoryImpl
    ): DataManagementRepository

    @Binds
    abstract fun bindLinkImportSource(
        impl: com.unchunks.echomark.data.importer.ContentResolverLinkImportSource
    ): com.unchunks.echomark.domain.importer.LinkImportSource

    @Binds
    abstract fun bindSubscriptionRepository(
        impl: com.unchunks.echomark.data.subscription.SubscriptionRepositoryImpl
    ): com.unchunks.echomark.domain.repository.SubscriptionRepository

    @Binds
    abstract fun bindPageSnapshotRepository(
        impl: com.unchunks.echomark.data.snapshot.PageSnapshotRepositoryImpl
    ): com.unchunks.echomark.domain.snapshot.PageSnapshotRepository

    @Binds
    abstract fun bindAttachmentRepository(
        impl: AttachmentStore
    ): AttachmentRepository

    @Binds
    abstract fun bindProcessingStatsRepository(
        impl: ProcessingStatsRepositoryImpl
    ): ProcessingStatsRepository

    @Binds
    abstract fun bindApiUsageRepository(
        impl: ApiUsageRepositoryImpl
    ): ApiUsageRepository

    @Binds
    abstract fun bindChatAttachmentSource(
        impl: ChatAttachmentLoader
    ): ChatAttachmentSource

    companion object {
        /** API キーの暗号化に使う Keystore の鍵。名前を変えると保存済みのキーを復号できなくなる */
        private const val API_KEY_ALIAS = "echomark_api_key_aes"

        @Provides
        @Singleton
        fun provideSecretCipher(): SecretCipher = KeystoreSecretCipher(API_KEY_ALIAS)
    }
}
