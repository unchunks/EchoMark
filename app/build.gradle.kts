plugins {
    // SDK・Java の版、release の R8 と署名、JVM テストの設定(components/build-logic)
    id("unchunks.android.application")
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.devtools.ksp)
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.androidx.room)
    alias(libs.plugins.kotlin.legacy.kapt)
    alias(libs.plugins.objectbox)
    alias(libs.plugins.roborazzi)
}

android {
    namespace = "com.unchunks.echomark"

    defaultConfig {
        applicationId = "com.unchunks.echomark"
        versionCode = 1
        versionName = "1.0"

        // ネイティブライブラリ(MediaPipe / ObjectBox など)を入れる ABI。
        // 実機は arm64-v8a、エミュレータ用に x86_64。32bit(armeabi-v7a / x86)は対象外にする
        // (ネイティブライブラリが ABI ごとに 50〜80MB あり、全 ABI 同梱だと APK が 4 倍近くになるため)
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    buildFeatures {
        compose = true
    }

    androidResources {
        // MediaPipe はモデル(assets の .task)を AssetFileDescriptor 経由でファイルとして直接読むため、無圧縮で格納する
        noCompress += "task"
    }

    packaging {
        resources {
            // PdfBox-Android が依存する BouncyCastle の耐量子暗号(PQC)のパラメータ表(約 4MB)。
            // PDF の復号(RC4/AES・証明書)では使わない
            excludes += "org/bouncycastle/pqc/**"
        }
        jniLibs {
            // tasks-text に同梱される生成 AI 系テキストタスク(TextProofreader / TextSummarizer)専用のネイティブライブラリ。
            // このアプリが使う TextEmbedder は tasks-core の libmediapipe_tasks_jni.so を読み込み、これは読み込まない
            // (System.loadLibrary("mediapipe_tasks_textgenai_jni") を呼ぶのは上記2クラスだけ)
            excludes += "**/libmediapipe_tasks_textgenai_jni.so"
        }
    }

    // Room のマイグレーションテスト(Robolectric)で MigrationTestHelper がスキーマ JSON を assets から読めるようにする。
    // JVM 単体テストは test ソースセットの assets を使わず、debug の統合済み assets を読むため debug に追加する
    // (debug APK にだけ数十 KB の JSON が入る。release には入らない)
    sourceSets {
        getByName("debug") {
            assets.directories.add("$projectDir/schemas")
        }
        // 検索品質の評価データ(JVM テストと共通の src/test/resources/eval)を、計測テスト(androidTest)の assets として読めるようにする
        getByName("androidTest") {
            assets.directories.add("$projectDir/src/test/resources/eval")
        }
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    // 再利用する部品(components)
    implementation(libs.unchunks.core)
    implementation(libs.unchunks.llm.core)
    implementation(libs.unchunks.llm.cloud)
    implementation(libs.unchunks.embedding)
    implementation(libs.unchunks.search)
    implementation(libs.unchunks.web.content)
    implementation(libs.unchunks.secure.storage)
    implementation(libs.unchunks.speech)
    implementation(libs.unchunks.document.text)
    implementation(libs.unchunks.llm.ondevice)
    implementation(libs.unchunks.file.storage)
    implementation(libs.unchunks.compose.ui)

    // Core / Lifecycle
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    implementation(libs.datastore.preferences)
    implementation(libs.timber)

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    // 大きな画面向けの配置(画面サイズクラス・折り目の情報と、一覧と詳細を左右に並べる ListDetailPaneScaffold)。
    // バージョンは Compose BOM で管理する(material3 と組み合わせの合ったものを使うため)
    implementation(libs.androidx.compose.material3.adaptive)
    implementation(libs.androidx.compose.material3.adaptive.layout)
    // アイコン一式(バージョンは Compose BOM で管理)。release の肥大化は R8 有効化で対処する
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)

    // Hilt
    implementation(libs.hilt.android)
    implementation(libs.hilt.navigation.compose)
    ksp(libs.hilt.android.compiler)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // WorkManager
    implementation(libs.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    // AI
    implementation(libs.mediapipe.tasks.text)
    implementation(libs.mediapipe.tasks.genai)
    // 端末内 AI の .litertlm 形式のモデル(Qwen など、SentencePiece 以外のトークナイザーのモデル)。
    // MediaPipe の LLM Inference は .litertlm のうち SentencePiece のモデルしか読めないため、こちらで動かす
    implementation(libs.litertlm.android)
    // Claude API 公式 SDK
    implementation(libs.anthropic.java)

    // 画像読み込み(OG 画像のサムネイル)。通信は共通の OkHttpClient を使う(EchoMarkApplication)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    // ホーム画面ウィジェット(Jetpack Glance)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)

    // ネットワーク・HTML解析(URL本文取得)
    implementation(libs.okhttp)
    implementation(libs.jsoup)

    // ファイルの中身の取り出し(端末内)
    // PDF のテキスト・タイトル(メタデータ)の取り出し。ページの画像化は端末の PdfRenderer を使う
    implementation(libs.pdfbox.android)
    // 画像・スキャンした PDF の文字の読み取り(OCR。日本語+ラテン文字のモデルをアプリに同梱し、オフラインで動く)
    implementation(libs.mlkit.text.recognition.japanese)
    // 画像に写っているものの手がかり(ラベル)。ラベルは補助的な情報のため、モデルを同梱する版(ネイティブライブラリが
    // ABI ごとに約 11MB)ではなく、Google Play 開発者サービスがモデルを配信する版(アプリへの追加は数百 KB)を使う
    implementation(libs.mlkit.image.labeling)
    // 画像の撮影日時・向き(EXIF)
    implementation(libs.androidx.exifinterface)

    // Testing
    testImplementation(libs.unchunks.testing)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver3)
    // android.jar のスタブ(org.json)は JVM テストで動かないため、実装を差し替える
    testImplementation(libs.org.json)
    // スクリーンショットテスト(Robolectric + Roborazzi)。使い方は docs/screenshot-testing.md
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.androidx.room.testing)
    // WorkManager のテスト用実装(WorkManagerTestInitHelper)。ワークの登録・取り消しの確認に使う
    testImplementation(libs.work.testing)
    testImplementation(libs.androidx.glance.appwidget.testing)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)

    // Debug
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

// ObjectBoxのタスクをConfiguration Cacheの対象外にする
tasks.matching { it.name.contains("objectbox", ignoreCase = true) }.configureEach {
    notCompatibleWithConfigurationCache("ObjectBox plugin is not yet fully compatible with Configuration Cache")
}

// ObjectBox の注釈処理(kapt)は debug / release で同じ app/objectbox-models/default.json を読み書きする。
// assembleDebug と assembleRelease を同時に実行すると両方の kapt が並行に走り、Windows では
// default.json の置き換え(.bak 作成)に失敗してビルドが落ちるため、kapt の注釈処理を1つずつ実行させる
abstract class ObjectBoxModelFileLock : BuildService<BuildServiceParameters.None>

val objectBoxModelFileLock = gradle.sharedServices.registerIfAbsent("objectBoxModelFileLock", ObjectBoxModelFileLock::class) {
    maxParallelUsages.set(1)
}
tasks.matching { it.name.startsWith("kapt") && !it.name.startsWith("kaptGenerateStubs") }.configureEach {
    usesService(objectBoxModelFileLock)
}