plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.devtools.ksp)
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.androidx.room)
    alias(libs.plugins.kotlin.legacy.kapt)
    alias(libs.plugins.objectbox)
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
        }
    }

    androidResources {
        noCompress += "task"
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
    // Claude API 公式 SDK
    implementation(libs.anthropic.java)

    // Network
    implementation(libs.okhttp)

    // ネットワーク・HTML解析(URL本文取得)
    implementation(libs.okhttp)
    implementation(libs.jsoup)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver3)
    // android.jar のスタブ(org.json)は JVM テストで動かないため、実装を差し替える
    testImplementation(libs.org.json)
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