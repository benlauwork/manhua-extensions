import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Lai Manhua"
    // Keep the extension code above both 1.6.4 and 1.4.2005 so Tachimanga replaces either build.
    versionCode = 2006
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
