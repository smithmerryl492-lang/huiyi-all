import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

fun configValue(name: String, defaultValue: String): String =
    providers.gradleProperty(name)
        .orElse(providers.environmentVariable(name))
        .orElse(defaultValue)
        .get()

fun buildConfigString(value: String): String =
    "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

val signingProperties = Properties()
val signingPropertiesFile = rootProject.file("signing/keystore.properties")
if (signingPropertiesFile.isFile) {
    signingPropertiesFile.inputStream().use(signingProperties::load)
}

fun signingValue(name: String): String? =
    providers.gradleProperty(name).orNull
        ?: providers.environmentVariable(name).orNull
        ?: signingProperties.getProperty(name)

val hasReleaseSigning = listOf(
    "HUIYI_RELEASE_STORE_FILE",
    "HUIYI_RELEASE_STORE_PASSWORD",
    "HUIYI_RELEASE_KEY_ALIAS",
    "HUIYI_RELEASE_KEY_PASSWORD",
).all { !signingValue(it).isNullOrBlank() }

android {
    namespace = "com.huiyi.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.huiyi.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    flavorDimensions += "environment"
    productFlavors {
        create("local") {
            dimension = "environment"
            versionNameSuffix = "-local"
            buildConfigField(
                "String",
                "HUIXIAO_API_BASE_URL",
                buildConfigString(configValue("HUIXIAO_LOCAL_API_BASE_URL", "http://10.0.2.2:8080/api/v1"))
            )
            buildConfigField(
                "String",
                "HUIXIAO_LIVE_WS_URL",
                buildConfigString(configValue("HUIXIAO_LOCAL_LIVE_WS_URL", "ws://10.0.2.2:8080/api/v1/live/ws"))
            )
        }

        create("staging") {
            dimension = "environment"
            versionNameSuffix = "-staging"
            buildConfigField(
                "String",
                "HUIXIAO_API_BASE_URL",
                buildConfigString(configValue("HUIXIAO_STAGING_API_BASE_URL", "http://43.154.197.96:28080/api/v1"))
            )
            buildConfigField(
                "String",
                "HUIXIAO_LIVE_WS_URL",
                buildConfigString(configValue("HUIXIAO_STAGING_LIVE_WS_URL", "ws://43.154.197.96:28080/api/v1/live/ws"))
            )
        }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(signingValue("HUIYI_RELEASE_STORE_FILE")!!)
                storePassword = signingValue("HUIYI_RELEASE_STORE_PASSWORD")
                keyAlias = signingValue("HUIYI_RELEASE_KEY_ALIAS")
                keyPassword = signingValue("HUIYI_RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation(platform("androidx.compose:compose-bom:2026.05.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("com.squareup.okhttp3:okhttp:5.3.2")
    implementation("com.alipay.sdk:alipaysdk-android:15.8.42")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
