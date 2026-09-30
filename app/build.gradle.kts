plugins {
    alias(libs.plugins.android.application)
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
    compileSdk =37

    defaultConfig {
        applicationId = "com.unchunks.echomark"
        minSdk = 31
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            // SavedStateHandle 等が触る Android スタブで落ちないようにする
            isReturnDefaultValues = true
            // Robolectric(スクリーンショットテスト等)でリソース・assets を読めるようにする
            isIncludeAndroidResources = true
        }
    }

    androidResources {
        noCompress += "task"
    }

    // Room のマイグレーションテスト(Robolectric)で MigrationTestHelper がスキーマ JSON を assets から読めるようにする。
    // JVM 単体テストは test ソースセットの assets を使わず、debug の統合済み assets を読むため debug に追加する
    // (debug APK にだけ数十 KB の JSON が入る。release には入らない)
    sourceSets {
        getByName("debug") {
            assets.directories.add("$projectDir/schemas")
        }
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
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

    // Network
    implementation(libs.okhttp)

    // 画像読み込み(OG 画像のサムネイル)。通信は共通の OkHttpClient を使う(EchoMarkApplication)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    // ネットワーク・HTML解析(URL本文取得)
    implementation(libs.okhttp)
    implementation(libs.jsoup)

    // Testing
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
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)

    // Debug
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

// Robolectric が JDK 17 以降で FileDescriptor 等の JDK 内部へアクセスできるようにする
tasks.withType<Test>().configureEach {
    jvmArgs(
        "--add-opens=java.base/java.io=ALL-UNNAMED",
        "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED"
    )
}

// ObjectBoxのタスクをConfiguration Cacheの対象外にする
tasks.matching { it.name.contains("objectbox", ignoreCase = true) }.configureEach {
    notCompatibleWithConfigurationCache("ObjectBox plugin is not yet fully compatible with Configuration Cache")
}