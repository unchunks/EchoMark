package com.unchunks.echomark.data.importer

/**
 * RFC 4180 の CSV を読む(引用符で囲んだ欄・欄の中の改行・「""」で表す引用符・先頭の BOM)。
 * 区切りはカンマ。タブ区切り(TSV)は [readCsv] の [delimiter] で指定する。
 * 空行は読み飛ばす。引用符が閉じていないときは、そこまでを1つの欄として読む(壊れたファイルでも止まらない)。
 */
internal fun readCsv(text: String, delimiter: Char = ','): List<List<String>> {
    val rows = mutableListOf<List<String>>()
    var row = mutableListOf<String>()
    val field = StringBuilder()
    var inQuotes = false
    var fieldWasQuoted = false
    var index = if (text.startsWith(BYTE_ORDER_MARK)) 1 else 0

    fun endField() {
        row.add(field.toString())
        field.clear()
        fieldWasQuoted = false
    }

    fun endRow() {
        endField()
        // 空行(欄が1つで中身が空)は読み飛ばす
        if (!(row.size == 1 && row[0].isEmpty())) rows.add(row)
        row = mutableListOf()
    }

    while (index < text.length) {
        val c = text[index]
        when {
            inQuotes -> when {
                c == '"' && index + 1 < text.length && text[index + 1] == '"' -> {
                    field.append('"')
                    index++
                }
                c == '"' -> inQuotes = false
                else -> field.append(c)
            }
            c == '"' && field.isEmpty() && !fieldWasQuoted -> {
                inQuotes = true
                fieldWasQuoted = true
            }
            c == delimiter -> endField()
            c == '\r' -> {
                if (index + 1 < text.length && text[index + 1] == '\n') index++
                endRow()
            }
            c == '\n' -> endRow()
            else -> field.append(c)
        }
        index++
    }
    if (field.isNotEmpty() || fieldWasQuoted || row.isNotEmpty()) endRow()
    return rows
}

/** 先頭の BOM(U+FEFF)。ソースに文字そのものを書くと lint に見つかるため、コードポイントから作る */
internal val BYTE_ORDER_MARK: Char = 0xFEFF.toChar()
