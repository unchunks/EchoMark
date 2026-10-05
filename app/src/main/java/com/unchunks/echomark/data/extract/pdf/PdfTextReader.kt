package com.unchunks.echomark.data.extract.pdf

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.unchunks.echomark.domain.extract.ExtractedContent
import java.io.File
import kotlin.math.min

/** PDF から取り出したテキストとタイトル(メタデータ)。 */
data class PdfText(
    val text: String,
    val pageCount: Int,
    val title: String?,
    /** ページ数・文字数の上限で途中までにした */
    val truncated: Boolean
)

/**
 * PdfBox-Android で PDF のテキストとタイトルを取り出す(端末内)。
 * 日本語の PDF でよく使われる定義済み CMap(UniJIS-UCS2-H など)も、ライブラリ同梱の CMap で Unicode に変換できる。
 */
object PdfTextReader {

    /** テキストを取り出すページ数の上限(これより後のページは読まない) */
    const val MAX_TEXT_PAGES = 300

    /** 読み込みに使うメモリの上限。超えた分は一時ファイルに置く(大きな PDF でメモリ不足にしない) */
    private const val MAX_MAIN_MEMORY_BYTES = 16L * 1024 * 1024

    @Volatile
    private var initialized = false

    /** 同梱のフォント・CMap を読めるようにする(最初の1回だけ)。 */
    fun ensureInitialized(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (!initialized) {
                PDFBoxResourceLoader.init(context.applicationContext)
                initialized = true
            }
        }
    }

    /**
     * [file] のテキストを先頭から取り出す。[ExtractedContent.MAX_TEXT_LENGTH] 字を超えたら、そこでやめる。
     * パスワード付き・壊れた PDF は例外(IOException など)を投げる。
     */
    fun read(file: File, tempDir: File, maxChars: Int = ExtractedContent.MAX_TEXT_LENGTH): PdfText {
        PDDocument.load(file, MemoryUsageSetting.setupMixed(MAX_MAIN_MEMORY_BYTES).setTempDir(tempDir)).use { document ->
            val pageCount = document.numberOfPages
            val title = document.documentInformation?.title?.trim()?.takeIf { it.isNotEmpty() }
            val lastPage = min(pageCount, MAX_TEXT_PAGES)
            val stripper = PDFTextStripper()
            val text = StringBuilder()
            var page = 1
            while (page <= lastPage && text.length <= maxChars) {
                stripper.startPage = page
                stripper.endPage = page
                text.append(stripper.getText(document))
                page++
            }
            val truncated = page <= pageCount || text.length > maxChars
            return PdfText(text.toString(), pageCount, title, truncated)
        }
    }
}

/** 取り出したテキストが、テキストを持たない(スキャンした)PDF のものかの判断。 */
object PdfTextQuality {

    /** 1ページあたりの意味のある文字がこれより少なければ、スキャンした PDF とみなして OCR する */
    const val MIN_CHARS_PER_PAGE = 20

    /**
     * 意味のある文字(文字・数字)の数。空白・記号のほか、Unicode に変換できなかった文字
     * (私用領域・制御文字。フォントに対応表が無いと出る)は数えない。
     */
    fun meaningfulCharCount(text: String): Int = text.count { it.isLetterOrDigit() }

    /** [pageCount] ページ(テキストを読んだページ数)に対して文字が少なすぎるか。 */
    fun looksScanned(text: String, pageCount: Int): Boolean {
        if (pageCount <= 0) return meaningfulCharCount(text) == 0
        val pages = min(pageCount, PdfTextReader.MAX_TEXT_PAGES)
        return meaningfulCharCount(text) < MIN_CHARS_PER_PAGE * pages
    }
}
