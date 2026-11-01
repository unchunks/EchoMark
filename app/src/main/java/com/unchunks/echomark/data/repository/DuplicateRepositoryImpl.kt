package com.unchunks.echomark.data.repository

import com.unchunks.components.core.DispatcherProvider
import com.unchunks.components.embedding.EmbeddingProvider
import com.unchunks.echomark.data.local.dao.BookmarkDao
import com.unchunks.echomark.data.local.dao.TagDao
import com.unchunks.echomark.data.local.objectbox.VectorSearchDataSource
import com.unchunks.echomark.data.mapper.toDomain
import com.unchunks.echomark.domain.bookmark.duplicates.BookmarkMerge
import com.unchunks.echomark.domain.bookmark.duplicates.DuplicateDetector
import com.unchunks.echomark.domain.bookmark.duplicates.DuplicatePair
import com.unchunks.echomark.domain.bookmark.duplicates.DuplicateSensitivity
import com.unchunks.echomark.domain.bookmark.duplicates.VectorNeighbor
import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.domain.model.TagSource
import com.unchunks.echomark.domain.repository.AppSettingsRepository
import com.unchunks.echomark.domain.repository.BookmarkRepository
import com.unchunks.echomark.domain.repository.DuplicateMatch
import com.unchunks.echomark.domain.repository.DuplicateRepository
import com.unchunks.echomark.domain.repository.DuplicateScan
import com.unchunks.echomark.domain.repository.MergeUndo
import com.unchunks.echomark.domain.repository.RelatedGroup
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DuplicateRepositoryImpl @Inject constructor(
    private val bookmarkDao: BookmarkDao,
    private val tagDao: TagDao,
    private val vectorSearch: VectorSearchDataSource,
    private val embeddingProvider: EmbeddingProvider,
    private val appSettings: AppSettingsRepository,
    private val bookmarkRepository: BookmarkRepository,
    private val dispatcherProvider: DispatcherProvider
) : DuplicateRepository {

    private companion object {
        /** 1件につき近い順に何件まで調べるか(自分自身を含まない)。多いほど大きなまとまりを見つけるが、遅くなる */
        const val NEIGHBORS_PER_BOOKMARK = 8

        /** `IN (...)` に渡す ID の数の上限(SQLite の変数の数の制限に収める) */
        const val ID_CHUNK = 500
    }

    override suspend fun scan(sensitivity: DuplicateSensitivity): DuplicateScan = withContext(dispatcherProvider.io) {
        val existing = bookmarkDao.getAllIds().toSet()
        val version = embeddingProvider.modelVersion
        // 削除済みのブックマークの埋め込みが残っていても、候補に出さない
        val neighbors = collectNeighbors(version, sensitivity.relatedDistance).filter {
            it.bookmarkId in existing && it.otherId in existing
        }
        val urls = bookmarkDao.getUrlEntries()

        val dismissed = appSettings.dismissedDuplicates.first()
        val stale = dismissed.filter { it.firstId !in existing || it.secondId !in existing }
        if (stale.isNotEmpty()) appSettings.updateDismissedDuplicates { it - stale.toSet() }

        val candidates = DuplicateDetector.candidates(urls, neighbors, sensitivity, dismissed)
        val clusters = DuplicateDetector.relatedClusters(neighbors, sensitivity)

        val needed = (candidates.flatMap { listOf(it.pair.firstId, it.pair.secondId) } + clusters.flatMap { it.ids }).toSet()
        val byId = loadBookmarks(needed)

        val matches = candidates.mapNotNull { candidate ->
            val first = byId[candidate.pair.firstId] ?: return@mapNotNull null
            val second = byId[candidate.pair.secondId] ?: return@mapNotNull null
            DuplicateMatch(
                first = first,
                second = second,
                reason = candidate.reason,
                distance = candidate.distance,
                suggestedKeepId = DuplicateDetector.suggestKeeper(first, second).id
            )
        }
        val groups = clusters.mapNotNull { cluster ->
            // 読み込めなかったもの(直前に削除されたなど)を除いて、まとまりの人数に足りなければ出さない
            val members = cluster.ids.mapNotNull { byId[it] }
            if (members.size < DuplicateDetector.MIN_GROUP_SIZE) null
            else RelatedGroup(DuplicateDetector.groupLabel(members), members)
        }
        val total = bookmarkDao.count()
        val embedded = vectorSearch.countByModelVersion(version).toInt().coerceAtMost(total)
        DuplicateScan(duplicates = matches, groups = groups, embeddedCount = embedded, totalCount = total)
    }

    override suspend fun keepBoth(pair: DuplicatePair) {
        appSettings.updateDismissedDuplicates { it + pair }
    }

    override suspend fun merge(keepId: Long, removeId: Long): MergeUndo? = withContext(dispatcherProvider.io) {
        if (keepId == removeId) return@withContext null
        val rows = bookmarkDao.getByIdsWithTags(listOf(keepId, removeId)).map { it.toDomain() }.associateBy { it.id }
        val keep = rows[keepId] ?: return@withContext null
        val remove = rows[removeId] ?: return@withContext null

        val fields = BookmarkMerge.merge(keep, remove)
        val tagsToAdd = BookmarkMerge.tagsToAdd(keep, remove)
        writeFields(keepId, fields)
        // 付けた人も引き継ぐ(ユーザーのタグは、再処理で外れないままにする)
        val (aiTags, userTags) = tagsToAdd.partition { it in remove.aiTags }
        tagDao.addTagsToBookmark(keepId, userTags, TagSource.USER)
        tagDao.addTagsToBookmark(keepId, aiTags, TagSource.AI)

        // オフラインコピーを残す方へ移したときは、消す方の削除でファイルの掃除を始めないよう、参照を外して渡す
        val movedSnapshot = fields.snapshotPath != keep.snapshotPath
        val toDelete = if (movedSnapshot) remove.copy(snapshotPath = null, snapshotPdfPath = null, snapshotAt = null) else remove
        bookmarkRepository.deleteBookmark(toDelete)
        Timber.i("重複を統合: 残す id=$keepId / 消す id=$removeId")
        MergeUndo(keptBefore = keep, removed = remove, addedTags = tagsToAdd.filter { it !in keep.tags })
    }

    override suspend fun undoMerge(undo: MergeUndo) {
        withContext(dispatcherProvider.io) {
            val keepId = undo.keptBefore.id
            writeFields(keepId, BookmarkMerge.Fields.of(undo.keptBefore))
            bookmarkRepository.restoreBookmark(undo.removed)
            // 統合で足したタグを外す。「外した」とは覚えさせない(AI が付け直せるように)
            undo.addedTags.forEach { name ->
                tagDao.getTagByName(name)?.let { tagDao.deleteCrossRef(keepId, it.id) }
            }
            tagDao.deleteOrphanAiTags()
        }
    }

    private suspend fun writeFields(id: Long, fields: BookmarkMerge.Fields) {
        bookmarkDao.updateMergedFields(
            id = id, createdAt = fields.createdAt, lastAccessedAt = fields.lastAccessedAt,
            isFavorite = fields.isFavorite, isArchived = fields.isArchived, readAt = fields.readAt,
            content = fields.content, snapshotPath = fields.snapshotPath,
            snapshotPdfPath = fields.snapshotPdfPath, snapshotAt = fields.snapshotAt
        )
    }

    /** 現在の版の全ベクトルについて、近い順に [NEIGHBORS_PER_BOOKMARK] 件のうち [maxDistance] 以下のものを集める。 */
    private suspend fun collectNeighbors(modelVersion: String, maxDistance: Double): List<VectorNeighbor> {
        val context = currentCoroutineContext()
        val neighbors = ArrayList<VectorNeighbor>()
        vectorSearch.forEachByModelVersion(modelVersion) { bookmarkId, vector ->
            // 数千件を調べるので、画面を離れて取り消されたらすぐやめる
            context.ensureActive()
            vectorSearch.nearestNeighbors(vector, NEIGHBORS_PER_BOOKMARK + 1, modelVersion)
                .filter { it.bookmarkId != bookmarkId && it.score <= maxDistance }
                .mapTo(neighbors) { VectorNeighbor(bookmarkId, it.bookmarkId, it.score) }
        }
        return neighbors
    }

    private suspend fun loadBookmarks(ids: Set<Long>): Map<Long, Bookmark> =
        ids.chunked(ID_CHUNK)
            .flatMap { bookmarkDao.getByIdsWithTags(it) }
            .map { it.toDomain() }
            .associateBy { it.id }
}
