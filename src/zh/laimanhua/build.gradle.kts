import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Lai Manhua"
    versionCode = 2
    contentWarning = ContentWarning.MIXED
    libVersion = "1.6"

    source {
        lang = "zh"
        name = "来漫画"
        baseUrl = "https://www.laimanhua88.com"
    }

    deeplink {
        path("/kanmanhua/..*")
    }
}
