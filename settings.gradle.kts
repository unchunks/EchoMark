pluginManagement {
    // 再利用する部品(components リポジトリの android/)の場所。部品が無ければビルドできない。
    // gradle.properties の unchunks.componentsDir か環境変数 UNCHUNKS_COMPONENTS_DIR で指定する。
    // 指定が無ければ、このディレクトリから親をたどって最初に見つかった components/android/ を使う
    // (unchunks/AndroidStudio/EchoMark からは unchunks/components/android。.claude/worktrees/ の下の作業ツリーからも見つかる)。
    // pluginManagement はほかの部分より先に別に評価されるため、外の変数を使えず、同じ探し方を下でもう一度書く
    val componentsDir = (providers.gradleProperty("unchunks.componentsDir").orNull ?: System.getenv("UNCHUNKS_COMPONENTS_DIR"))
        ?.let { settingsDir.resolve(it) }
        ?: generateSequence(settingsDir.parentFile) { it.parentFile }
            .map { it.resolve("components/android") }
            .firstOrNull { it.resolve("settings.gradle.kts").isFile }
        ?: error(
            "部品(components/android)が見つかりません。EchoMark の親のディレクトリのどれかに components リポジトリ" +
                "(git@github.com:unchunks/components.git)を clone するか、unchunks.componentsDir で場所を指定してください"
        )
    // アプリの共通設定(unchunks.android.application)などの規約プラグイン
    includeBuild(componentsDir.resolve("build-logic"))
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google\\.android.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "EchoMark"
include(":app")

// 部品は composite build で使う。app の依存に書いた com.unchunks.components:<部品> を、Gradle が components のプロジェクトに置き換える
val componentsDir = (providers.gradleProperty("unchunks.componentsDir").orNull ?: System.getenv("UNCHUNKS_COMPONENTS_DIR"))
    ?.let { settingsDir.resolve(it) }
    ?: generateSequence(settingsDir.parentFile) { it.parentFile }
        .map { it.resolve("components/android") }
        .first { it.resolve("settings.gradle.kts").isFile }
includeBuild(componentsDir)
