package com.unchunks.echomark.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * v31 で追加。ブックマークの要約・チャットの回答を Web で確かめた結果。対象ごとに1つ(確かめ直すと置き換える)。
 * 対象はブックマークかメッセージのどちらかなので外部キーにはせず、対象が消えたものは確かめるときに消す。
 * バックアップには入れない(確かめた時点の手がかりで、もう一度確かめられる)。
 * @property targetType FactCheckTargetType の名前
 * @property textHash 確かめた文章のハッシュ(文章が変わったら古い結果として出す)
 * @property claims 主張ごとの結果(JSON の配列。FactCheckJson)
 */
@Entity(
    tableName = "fact_checks",
    indices = [Index(value = ["targetType", "targetId"], unique = true)]
)
data class FactCheckEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val targetType: String,
    val targetId: Long,
    val textHash: String,
    val claims: String,
    val checkedAt: Long
)
