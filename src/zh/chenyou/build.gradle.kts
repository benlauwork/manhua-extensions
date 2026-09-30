import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Chenyou"
    versionCode = 1
    contentWarning = ContentWarning.MIXED
    // Tachimanga requires the legacy 1.4 source interface.
    libVersion = "1.4"

    source {
        name = "尘柚漫画"
        baseUrl = "https://chenyouapp.com"
        lang = "zh"
    }
}
