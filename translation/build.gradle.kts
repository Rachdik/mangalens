import java.util.Properties

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

// Kunci API LLM dibaca dari .env di root proyek (tidak di-commit). Bila file
// tidak ada, build tetap berjalan dan provider LLM melaporkan bahwa kunci
// belum diisi.
val env = Properties().apply {
    val file = rootProject.file(".env")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun envValue(vararg names: String, fallback: String = ""): String {
    for (name in names) {
        val value = env.getProperty(name)?.trim()
        if (!value.isNullOrEmpty()) return value
    }
    return fallback
}

fun quoted(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "com.mangalens.translation"
    compileSdk = 34

    defaultConfig {
        minSdk = 26

        buildConfigField(
            "String",
            "LLM_API_KEY",
            quoted(envValue("LLM_API_KEY", "ZAI_API_KEY"))
        )
        buildConfigField(
            "String",
            "LLM_BASE_URL",
            quoted(envValue("LLM_BASE_URL", "ZAI_API_BASE", fallback = "https://api.z.ai/api/paas/v4"))
        )
        buildConfigField(
            "String",
            "LLM_MODEL",
            quoted(envValue("LLM_MODEL", fallback = "glm-4-flash"))
        )
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    api(project(":core"))

    // Terjemahan on-device; model diunduh sekali saat pertama dipakai.
    implementation("com.google.mlkit:translate:17.0.3")
}
