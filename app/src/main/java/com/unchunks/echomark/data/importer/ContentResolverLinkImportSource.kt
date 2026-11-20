package com.unchunks.echomark.data.importer

import android.content.Context
import androidx.core.net.toUri
import com.unchunks.components.core.DispatcherProvider
import com.unchunks.echomark.domain.importer.ImportParseResult
import com.unchunks.echomark.domain.importer.LinkImportException
import com.unchunks.echomark.domain.importer.LinkImportSource
import com.unchunks.echomark.domain.importer.MAX_IMPORT_BYTES
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import javax.inject.Inject

/** ファイル選択で得た URI のファイルを、UTF-8 の文字列として読んで [LinkImportParser] にかける。 */
class ContentResolverLinkImportSource @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val dispatcherProvider: DispatcherProvider
) : LinkImportSource {

    override suspend fun read(uri: String): ImportParseResult {
        val text = withContext(dispatcherProvider.io) {
            try {
                val input = context.contentResolver.openInputStream(uri.toUri())
                    ?: throw LinkImportException("ファイルを開けませんでした")
                input.use { readLimited(it, MAX_IMPORT_BYTES) }
            } catch (e: LinkImportException) {
                throw e
            } catch (e: SecurityException) {
                Timber.w(e, "取り込むファイルにアクセスできない")
                throw LinkImportException("ファイルにアクセスできませんでした", e)
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                Timber.w(e, "取り込むファイルの読み込みに失敗")
                throw LinkImportException("ファイルを読み込めませんでした", e)
            } catch (e: Exception) {
                // 提供元(ファイルアプリなど)の不具合で、想定外の例外が来ることもある
                Timber.w(e, "取り込むファイルを開けなかった")
                throw LinkImportException("ファイルを開けませんでした", e)
            }
        }
        // 数十 MB の解析は重いので、読み込みとは別に計算用のスレッドで行う
        return withContext(dispatcherProvider.default) {
            LinkImportParser.parse(text) ?: throw LinkImportException(UNSUPPORTED_MESSAGE)
        }
    }

    private companion object {
        const val UNSUPPORTED_MESSAGE =
            "このファイルの形式には対応していません。ブラウザ・Pocket・Raindrop.io・Instapaper・Pinboard などの書き出し(HTML・CSV・JSON)を選んでください"
    }
}

/** [input] を [maxBytes] まで UTF-8 の文字列として読む。超えたら [LinkImportException]。 */
internal fun readLimited(input: InputStream, maxBytes: Long): String {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(BUFFER_BYTES)
    var total = 0L
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        total += read
        if (total > maxBytes) {
            throw LinkImportException("ファイルが大きすぎます(${maxBytes / (1024 * 1024)}MB まで)")
        }
        output.write(buffer, 0, read)
    }
    return output.toString(Charsets.UTF_8.name())
}

private const val BUFFER_BYTES = 64 * 1024
