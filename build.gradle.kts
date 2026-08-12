import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Komga Gorse"
    versionCode = 1
    contentWarning = ContentWarning.SAFE
    libVersion = "1.4"

    source {
        name = "Komga Gorse"
        lang = "all"
        baseUrl = "https://127.0.0.1:25600"
        id = 8656569799497662329L
    }
    source {
        name = "Komga Gorse (2)"
        lang = "all"
        baseUrl = "https://127.0.0.1:25600"
        id = 5204932013275616902L
    }
    source {
        name = "Komga Gorse (3)"
        lang = "all"
        baseUrl = "https://127.0.0.1:25600"
        id = 5592401397387013854L
    }
}

dependencies {
    implementation("org.apache.commons:commons-text:1.11.0")
}
