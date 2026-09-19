import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Manga 160"
    // 1.4.2008 produces Android versionCode 106008, higher than the previous 1.6.6
    // build, while using the source interface supported natively by Tachimanga.
    versionCode = 2008
    contentWarning = ContentWarning.MIXED
    libVersion = "1.4"

    source {
        name = "漫畫160"
        baseUrl = "https://www.mh160mh.com"
        lang = "zh"
    }

    deeplink {
        path("/kanmanhua/..*")
    }
}
