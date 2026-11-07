package com.unchunks.echomark.testing

import com.unchunks.echomark.domain.bookmark.duplicates.DuplicatePair
import com.unchunks.echomark.domain.bookmark.duplicates.DuplicateSensitivity
import com.unchunks.echomark.domain.repository.DuplicateRepository
import com.unchunks.echomark.domain.repository.DuplicateScan
import com.unchunks.echomark.domain.repository.MergeUndo

/** 重複の整理の Fake。[scanResult] を返し、呼び出しを記録する。[failure] があれば探す・統合で投げる。 */
class FakeDuplicateRepository : DuplicateRepository {
    var scanResult = DuplicateScan(emptyList(), emptyList(), embeddedCount = 0, totalCount = 0)
    var failure: Throwable? = null

    /** 統合の結果。null なら「どちらかが無い」として扱う */
    var mergeResult: MergeUndo? = null

    val scanCalls = mutableListOf<DuplicateSensitivity>()
    val merges = mutableListOf<Pair<Long, Long>>()
    val keptBoth = mutableListOf<DuplicatePair>()
    val undone = mutableListOf<MergeUndo>()

    override suspend fun scan(sensitivity: DuplicateSensitivity): DuplicateScan {
        scanCalls += sensitivity
        failure?.let { throw it }
        return scanResult
    }

    override suspend fun keepBoth(pair: DuplicatePair) {
        failure?.let { throw it }
        keptBoth += pair
    }

    override suspend fun merge(keepId: Long, removeId: Long): MergeUndo? {
        failure?.let { throw it }
        merges += keepId to removeId
        return mergeResult
    }

    override suspend fun undoMerge(undo: MergeUndo) {
        undone += undo
    }
}
