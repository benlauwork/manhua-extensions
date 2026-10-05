import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Eight Comic"
    versionCode = 1
    contentWarning = ContentWarning.MIXED
    // Tachimanga uses the legacy source interface.
    libVersion = "1.4"

    source {
        name = "無限動漫 8comic"
        baseUrl = "https://www.8comic.com"
        lang = "zh"
    }

    deeplink {
        host("www.8comic.com")
        host("8comic.com")
        path("/html/..*")
    }

    deeplink {
        host("articles.onemoreplace.tw")
        path("/online/new-..*")
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
    testImplementation(libs.kotlin.stdlib)
}

// The source metadata processor only applies to the extension, not its unit tests.
tasks.matching { it.name.startsWith("ksp") && it.name.endsWith("UnitTestKotlin") }.configureEach {
    enabled = false
}
