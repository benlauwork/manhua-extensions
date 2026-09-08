import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Manhua 1234"
    versionCode = 2
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
