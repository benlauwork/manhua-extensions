import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Manga 160"
    versionCode = 2
    contentWarning = ContentWarning.MIXED
    libVersion = "1.6"

    source {
        name = "漫畫160"
        baseUrl = "https://www.mh160mh.com"
        lang = "zh"
    }

    deeplink {
        path("/kanmanhua/..*")
    }
}
