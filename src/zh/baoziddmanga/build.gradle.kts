import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Baozi Manhua (DDManga)"
    versionCode = 1
    contentWarning = ContentWarning.MIXED
    libVersion = "1.6"

    source {
        name = "包子漫畫 (DDManga)"
        lang = "zh"
        baseUrl = "https://www.ddmanga.com"
    }

    deeplink {
        host("www.ddmanga.com")
        host("cn.ddmanga.com")
        path("/comic/..*")
    }
}
