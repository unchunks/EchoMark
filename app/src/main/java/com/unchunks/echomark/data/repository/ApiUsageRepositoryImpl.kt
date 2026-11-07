package com.unchunks.echomark.data.repository

import com.unchunks.components.core.DispatcherProvider
import com.unchunks.components.llm.ApiProvider
import com.unchunks.echomark.data.local.dao.ApiUsageDao
import com.unchunks.echomark.data.local.entity.ApiUsageEntity
import com.unchunks.echomark.domain.repository.AiTask
import com.unchunks.echomark.domain.usage.ApiUsageRecord
import com.unchunks.echomark.domain.usage.ApiUsageRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** クラウド API の使用量を Room(api_usage)に持つ。 */
@Singleton
class ApiUsageRepositoryImpl @Inject constructor(
    private val dao: ApiUsageDao,
    private val dispatcherProvider: DispatcherProvider
) : ApiUsageRepository {

    override suspend fun record(usage: ApiUsageRecord) = withContext(dispatcherProvider.io) {
        dao.insert(
            ApiUsageEntity(
                createdAt = usage.createdAt,
                provider = usage.provider.name,
                model = usage.model,
                task = usage.task.name,
                inputTokens = usage.inputTokens,
                outputTokens = usage.outputTokens
            )
        )
        // 表示するのは今月までなので、保存する期間は十分に長く取って、それより古いものを消す
        dao.deleteBefore(usage.createdAt - RETENTION_MILLIS)
    }

    override fun observeSince(from: Long): Flow<List<ApiUsageRecord>> =
        dao.observeSince(from)
            .map { entities -> entities.mapNotNull { it.toDomain() } }
            .flowOn(dispatcherProvider.io)

    private fun ApiUsageEntity.toDomain(): ApiUsageRecord? {
        // 読めない提供元・用途(新しい版で増えたもの)の記録は飛ばす
        val provider = ApiProvider.entries.firstOrNull { it.name == provider } ?: return null
        val task = AiTask.entries.firstOrNull { it.name == task } ?: return null
        return ApiUsageRecord(createdAt, provider, model, task, inputTokens, outputTokens)
    }

    companion object {
        /** 記録を残す期間(約 13 か月) */
        const val RETENTION_MILLIS = 400L * 24 * 60 * 60 * 1000
    }
}
