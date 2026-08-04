import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

fun loadPropertiesFile(file: File): Properties {
    val properties = Properties()
    if (file.exists()) {
        file.readLines().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isNotBlank() && !trimmed.startsWith("#")) {
                val separator = trimmed.indexOf("=")
                if (separator > 0) {
                    val key = trimmed.substring(0, separator).trim()
                    val value = trimmed.substring(separator + 1).trim().trim('"')
                    properties.setProperty(key, value)
                }
            }
        }
    }
    return properties
}

fun Properties.firstPresent(vararg keys: String): String? {
    return keys.firstNotNullOfOrNull { key -> getProperty(key)?.takeIf { it.isNotBlank() } }
}

fun envFirst(vararg keys: String): String? {
    return keys.firstNotNullOfOrNull { key -> System.getenv(key)?.takeIf { it.isNotBlank() } }
}

fun escapedBuildConfigString(value: String): String {
    return "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""
}

fun allPresent(vararg values: String?): Boolean {
    for (value in values) {
        if (value.isNullOrBlank()) return false
    }
    return true
}

android {
    namespace = "com.smartwatering.app"
    compileSdk = 36

    val localProperties = Properties()
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        localPropertiesFile.inputStream().use { localProperties.load(it) }
    }
    fun resolveBuildTypeValue(
        buildType: String,
        primaryKey: String,
        fallbackKey: String? = null,
        defaultValue: String = "",
    ): String {
        val suffix = buildType.uppercase()
        val keys = listOfNotNull(
            "${primaryKey}_$suffix",
            fallbackKey?.let { "${it}_$suffix" },
            primaryKey,
            fallbackKey,
        ).toTypedArray()
        val buildTypeEnvProperties = loadPropertiesFile(rootProject.file("app/.env.$buildType"))
        return localProperties.firstPresent(*keys)
            ?: envFirst(*keys)
            ?: buildTypeEnvProperties.firstPresent(primaryKey, fallbackKey ?: primaryKey)
            ?: defaultValue
    }

    fun configurePublicApiBuildConfig(buildType: com.android.build.api.dsl.ApplicationBuildType, name: String) {
        val baseUrl = resolveBuildTypeValue(
            name,
            "SMART_WATERING_PUBLIC_API_BASE_URL",
            "SMART_WATERING_PUBLIC_API_URL",
            "https://api.example.com/",
        )
        val googleWebClientId = resolveBuildTypeValue(
            name,
            "SMART_WATERING_GOOGLE_WEB_CLIENT_ID",
            defaultValue = "",
        )
        buildType.buildConfigField(
            "String",
            "SMART_WATERING_PUBLIC_API_BASE_URL",
            escapedBuildConfigString(baseUrl),
        )
        buildType.buildConfigField(
            "String",
            "SMART_WATERING_GOOGLE_WEB_CLIENT_ID",
            escapedBuildConfigString(googleWebClientId),
        )
    }

    defaultConfig {
        applicationId = "com.smartwatering.app"
        minSdk = 34
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    val releaseStoreFile = localProperties.getProperty("SMART_WATERING_RELEASE_STORE_FILE")
    val releaseStorePassword = localProperties.getProperty("SMART_WATERING_RELEASE_STORE_PASSWORD")
    val releaseKeyAlias = localProperties.getProperty("SMART_WATERING_RELEASE_KEY_ALIAS")
    val releaseKeyPassword = localProperties.getProperty("SMART_WATERING_RELEASE_KEY_PASSWORD")
    val hasReleaseSigning = allPresent(
        releaseStoreFile,
        releaseStorePassword,
        releaseKeyAlias,
        releaseKeyPassword
    )

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFile)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            configurePublicApiBuildConfig(this, "debug")
        }
        release {
            configurePublicApiBuildConfig(this, "release")
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        jniLibs {
            keepDebugSymbols += "**/libandroidx.graphics.path.so"
        }
    }
    lint {
        // SDK 37 is not installed locally. Major library upgrades are kept
        // separate from correctness and static-analysis fixes.
        disable += setOf("OldTargetApi", "GradleDependency", "NewerVersionAvailable")
    }
}

androidComponents {
    beforeVariants(selector().all()) { variantBuilder ->
        variantBuilder.enableAndroidTest = false
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.moshi)
    implementation(libs.okhttp.logging)
    implementation(libs.moshi.kotlin)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)
    implementation(libs.googleid)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
