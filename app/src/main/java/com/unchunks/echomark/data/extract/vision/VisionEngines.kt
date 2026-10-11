package com.unchunks.echomark.data.extract.vision

import android.graphics.Bitmap
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** 画像の文字を読み取る(OCR)。 */
interface TextRecognizerEngine {
    /**
     * [bitmap] に写っている文字を、行ごとに改行した文字列で返す。文字が無ければ空文字。
     * @param rotationDegrees 画像を正しい向きにするための回転(0/90/180/270。EXIF の向き)
     */
    suspend fun recognize(bitmap: Bitmap, rotationDegrees: Int = 0): String
}

/** 画像に写っているものの手がかり(ラベル)を付ける。 */
interface ImageLabelerEngine {
    /** 確からしい順のラベル(英語。例: "Food")。 */
    suspend fun label(bitmap: Bitmap, rotationDegrees: Int = 0): List<String>
}

/**
 * ML Kit Text Recognition v2(日本語モデル。ラテン文字も読める)。モデルはアプリに同梱され、端末内で動く。
 * クライアントは使うたびに作って閉じる(常駐させずにメモリを返す)。
 */
@Singleton
class MlKitTextRecognizerEngine @Inject constructor() : TextRecognizerEngine {
    override suspend fun recognize(bitmap: Bitmap, rotationDegrees: Int): String {
        val client = TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
        return try {
            client.process(InputImage.fromBitmap(bitmap, rotationDegrees)).await().text
        } finally {
            client.close()
        }
    }
}

/** ML Kit Image Labeling(既定の約 400 ラベルのモデル。同梱・端末内)。 */
@Singleton
class MlKitImageLabelerEngine @Inject constructor() : ImageLabelerEngine {
    override suspend fun label(bitmap: Bitmap, rotationDegrees: Int): List<String> {
        val options = ImageLabelerOptions.Builder().setConfidenceThreshold(MIN_CONFIDENCE).build()
        val client = ImageLabeling.getClient(options)
        return try {
            client.process(InputImage.fromBitmap(bitmap, rotationDegrees)).await()
                .sortedByDescending { it.confidence }
                .map { it.text }
        } finally {
            client.close()
        }
    }

    private companion object {
        /** これより自信の低いラベルは使わない(誤ったタグ付けを避ける) */
        const val MIN_CONFIDENCE = 0.7f
    }
}

/** Play services の Task の完了を待つ(キャンセルしても処理自体は止まらないが、結果は捨てる)。 */
internal suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { result -> if (cont.isActive) cont.resume(result) }
    addOnFailureListener { e -> if (cont.isActive) cont.resumeWithException(e) }
    addOnCanceledListener { cont.cancel() }
}
