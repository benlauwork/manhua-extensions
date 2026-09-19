import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Lai Manhua"
    // 1.4.2005 produces Android versionCode 106005, one higher than 1.6.4.
    versionCode = 2005
    contentWarning = ContentWarning.MIXED
    libVersion = "1.4"

    source {
        lang = "zh"
        name = "来漫画"
        baseUrl = "https://www.laimanhua88.com"
    }

    deeplink {
        path("/kanmanhua/..*")
    }
}
