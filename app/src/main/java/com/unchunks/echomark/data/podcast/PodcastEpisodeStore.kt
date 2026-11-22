package com.unchunks.echomark.data.podcast

import com.unchunks.components.speechtext.ReadingEntry
import com.unchunks.echomark.domain.podcast.EpisodeLine
import com.unchunks.echomark.domain.podcast.EpisodeSource
import com.unchunks.echomark.domain.podcast.EpisodeStatus
import com.unchunks.echomark.domain.podcast.PodcastEpisode
import com.unchunks.echomark.domain.podcast.PodcastFormat
import com.unchunks.echomark.domain.podcast.PodcastSpeaker
import com.unchunks.echomark.domain.podcast.ScriptGenerator
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import timber.log.Timber
import java.io.File

/**
 * エピソードを、アプリの中のフォルダ([root]、`filesDir/podcasts`)に1つずつ保存する(DB には持たない)。
 * `<root>/<id>/episode.json` に台本と情報、`<root>/<id>/<audioFile>` に音声。
 * [root] は backup_rules.xml / data_extraction_rules.xml でバックアップから外す。
 *
 * 書き込みは一時ファイルに書いてから置き換える(途中で止まっても、壊れた JSON を残さない)。
 * 呼び出し側(1つのリポジトリ)で書き込みを順に行うこと。
 */
class PodcastEpisodeStore(private val root: File) {

    fun list(): List<PodcastEpisode> =
        root.listFiles { file -> file.isDirectory && ID.matches(file.name) }.orEmpty()
            .mapNotNull { read(it.name) }
            .sortedByDescending { it.createdAt }

    fun read(id: String): PodcastEpisode? {
        if (!ID.matches(id)) return null
        val file = File(dirOf(id), EPISODE_FILE)
        if (!file.isFile) return null
        return try {
            PodcastEpisodeJson.decode(file.readText())?.takeIf { it.id == id }
        } catch (e: Exception) {
            Timber.w(e, "エピソードを読めなかった: %s", id)
            null
        }
    }

    fun write(episode: PodcastEpisode) {
        require(ID.matches(episode.id)) { "エピソードの id が正しくない" }
        val dir = dirOf(episode.id).apply { mkdirs() }
        val temp = File(dir, "$EPISODE_FILE.tmp")
        temp.writeText(PodcastEpisodeJson.encode(episode))
        val target = File(dir, EPISODE_FILE)
        if (!temp.renameTo(target)) {
            target.delete()
            if (!temp.renameTo(target)) throw java.io.IOException("エピソードを保存できなかった")
        }
    }

    /** エピソードのフォルダ(音声・作業中のファイルを置く) */
    fun dirOf(id: String): File {
        require(ID.matches(id)) { "エピソードの id が正しくない" }
        return File(root, id)
    }

    fun audioFileOf(episode: PodcastEpisode): File? =
        episode.audioFile?.takeIf { FILE_NAME.matches(it) }?.let { File(dirOf(episode.id), it) }?.takeIf { it.isFile }

    fun delete(id: String): Boolean = ID.matches(id) && dirOf(id).deleteRecursively()

    fun deleteAll() {
        root.listFiles { file -> file.isDirectory && ID.matches(file.name) }.orEmpty().forEach { it.deleteRecursively() }
    }

    companion object {
        const val EPISODE_FILE = "episode.json"

        /** エピソードの id(フォルダの名前)。英数字とハイフンだけにして、ほかの場所を指せないようにする */
        val ID = Regex("""[A-Za-z0-9-]{1,64}""")
        private val FILE_NAME = Regex("""[A-Za-z0-9._-]{1,64}""")
    }
}

/** エピソードと JSON の変換。読めない値・知らない値は除く。 */
object PodcastEpisodeJson {
    fun encode(episode: PodcastEpisode): String = JSONObject().apply {
        put("version", VERSION)
        put("id", episode.id)
        put("title", episode.title)
        put("createdAt", episode.createdAt)
        put("format", episode.format.name)
        put("status", episode.status.name)
        put("sources", JSONArray(episode.sources.map { s ->
            JSONObject().put("bookmarkId", s.bookmarkId).put("title", s.title).putOpt("sourceName", s.sourceName).putOpt("url", s.url)
        }))
        put("lines", JSONArray(episode.lines.map { l ->
            JSONObject().put("speaker", l.speaker.name).put("text", l.text).put("spoken", l.spoken)
                .putOpt("sourceIndex", l.sourceIndex).putOpt("startMs", l.startMs)
        }))
        put("readings", JSONArray(episode.readings.map { JSONObject().put("surface", it.surface).put("reading", it.reading) }))
        putOpt("generator", episode.generator?.name)
        putOpt("audioFile", episode.audioFile)
        putOpt("durationMs", episode.durationMs)
        putOpt("sizeBytes", episode.sizeBytes)
        putOpt("failureReason", episode.failureReason)
    }.toString()

    fun decode(json: String): PodcastEpisode? {
        val o = try {
            JSONObject(json)
        } catch (e: JSONException) {
            return null
        }
        val id = o.optString("id").takeIf { it.isNotBlank() } ?: return null
        val format = PodcastFormat.entries.firstOrNull { it.name == o.optString("format") } ?: return null
        val status = EpisodeStatus.entries.firstOrNull { it.name == o.optString("status") } ?: return null
        return PodcastEpisode(
            id = id,
            title = o.optString("title"),
            createdAt = o.optLong("createdAt"),
            format = format,
            status = status,
            sources = o.optJSONArray("sources").objects().mapNotNull { s ->
                EpisodeSource(
                    bookmarkId = s.optLong("bookmarkId", -1).takeIf { it >= 0 } ?: return@mapNotNull null,
                    title = s.optString("title"),
                    sourceName = s.stringOrNull("sourceName"),
                    url = s.stringOrNull("url")?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
                )
            },
            lines = o.optJSONArray("lines").objects().mapNotNull { l ->
                EpisodeLine(
                    speaker = PodcastSpeaker.entries.firstOrNull { it.name == l.optString("speaker") } ?: return@mapNotNull null,
                    text = l.optString("text"),
                    spoken = l.optString("spoken"),
                    sourceIndex = if (l.has("sourceIndex")) l.optInt("sourceIndex") else null,
                    startMs = if (l.has("startMs")) l.optLong("startMs") else null
                )
            },
            readings = o.optJSONArray("readings").objects().mapNotNull { r ->
                val surface = r.optString("surface")
                val reading = r.optString("reading")
                if (ReadingEntry.problemOf(surface, reading) == null) ReadingEntry(surface, reading) else null
            },
            generator = ScriptGenerator.entries.firstOrNull { it.name == o.optString("generator") },
            audioFile = o.stringOrNull("audioFile"),
            durationMs = if (o.has("durationMs")) o.optLong("durationMs") else null,
            sizeBytes = if (o.has("sizeBytes")) o.optLong("sizeBytes") else null,
            failureReason = o.stringOrNull("failureReason")
        )
    }

    private const val VERSION = 1

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

    private fun JSONObject.stringOrNull(key: String): String? = if (has(key) && !isNull(key)) optString(key).takeIf { it.isNotEmpty() } else null
}
