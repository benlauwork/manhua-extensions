import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Manhua 1234"
    versionCode = 4
    contentWarning = ContentWarning.MIXED
    libVersion = "1.6"

    source {
        lang = "zh"
        name = "漫畫1234"
        baseUrl = "https://www.wmh1234.com"
    }

    deeplink {
        path("/comic/..*")
    }
}

android {
    sourceSets.named("test") {
        java.directories.add("test")
        kotlin.directories.add("test")
        resources.directories.add("test-resources")
    }
}

dependencies {
    testImplementation(libs.junit)
    testImplementation(libs.bundles.common)
    testImplementation(libs.tachiyomi.lib.v16)
}

// Source metadata is only generated for the extension itself.
tasks.matching { it.name.startsWith("ksp") && it.name.endsWith("UnitTestKotlin") }.configureEach {
    enabled = false
}
