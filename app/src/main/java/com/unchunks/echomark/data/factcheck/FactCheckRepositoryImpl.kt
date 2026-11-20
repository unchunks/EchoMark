package com.unchunks.echomark.data.factcheck

import com.unchunks.components.core.DispatcherProvider
import com.unchunks.echomark.data.local.dao.FactCheckDao
import com.unchunks.echomark.data.local.entity.FactCheckEntity
import com.unchunks.echomark.domain.factcheck.FactCheckProgress
import com.unchunks.echomark.domain.factcheck.FactCheckRepository
import com.unchunks.echomark.domain.factcheck.FactCheckResult
import com.unchunks.echomark.domain.factcheck.FactCheckTarget
import com.unchunks.echomark.domain.factcheck.FactCheckTargetType
import com.unchunks.echomark.domain.factcheck.FactCheckText
import com.unchunks.echomark.domain.factcheck.NothingToFactCheckException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Web で確かめた結果を DB(fact_checks)に残す。開き直したときは保存したものを出し、検索し直さない。
 * 確かめるたびに、対象(ブックマーク・回答)の消えた古い結果を消す。
 */
class FactCheckRepositoryImpl internal constructor(
    private val dao: FactCheckDao,
    private val checker: FactChecker,
    private val dispatcherProvider: DispatcherProvider,
    private val clock: () -> Long
) : FactCheckRepository {

    @Inject
    constructor(dao: FactCheckDao, checker: FactChecker, dispatcherProvider: DispatcherProvider) :
        this(dao, checker, dispatcherProvider, System::currentTimeMillis)

    override fun observe(target: FactCheckTarget): Flow<FactCheckResult?> =
        dao.observe(target.type.name, target.id).map { it?.toResult() }

    override suspend fun check(target: FactCheckTarget, text: String, onProgress: (FactCheckProgress) -> Unit): FactCheckResult {
        if (text.isBlank()) throw NothingToFactCheckException()
        val claims = checker.check(text, onProgress)
        if (claims.isEmpty()) throw NothingToFactCheckException()
        val result = FactCheckResult(target, FactCheckText.hash(text), claims, clock())
        withContext(dispatcherProvider.io) {
            dao.deleteOrphans()
            dao.upsert(
                FactCheckEntity(
                    targetType = target.type.name,
                    targetId = target.id,
                    textHash = result.textHash,
                    claims = FactCheckJson.encode(claims),
                    checkedAt = result.checkedAt
                )
            )
        }
        return result
    }

    private fun FactCheckEntity.toResult(): FactCheckResult? {
        val type = FactCheckTargetType.entries.firstOrNull { it.name == targetType } ?: return null
        return FactCheckResult(FactCheckTarget(type, targetId), textHash, FactCheckJson.decode(claims), checkedAt)
    }
}
