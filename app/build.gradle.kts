plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
val ciKeystorePath = System.getenv("CM_KEYSTORE_PATH")
val ciKeystorePassword = System.getenv("CM_KEYSTORE_PASSWORD")
val ciKeyAlias = System.getenv("CM_KEY_ALIAS")
val ciKeyPassword = System.getenv("CM_KEY_PASSWORD")
val ciSigningConfigured =
    !ciKeystorePath.isNullOrBlank() &&
    !ciKeystorePassword.isNullOrBlank() &&
    !ciKeyAlias.isNullOrBlank() &&
    !ciKeyPassword.isNullOrBlank()
val buildVersionCode =
    providers.gradleProperty("versionCode").orNull?.toIntOrNull()
        ?: (System.getenv("CM_VERSION_CODE")?.toIntOrNull() ?: 102)
val buildVersionName =
    providers.gradleProperty("versionName").orNull
        ?: ("1.1." + (System.getenv("CM_VERSION_CODE")?.takeLast(6) ?: buildVersionCode.toString()))
android {
    namespace="app.f1multiview"
    compileSdk=36
    defaultConfig {
        applicationId="app.f1multiview"
        minSdk=26
        targetSdk=35
        versionCode=buildVersionCode
        versionName=buildVersionName
    }
    buildFeatures { compose=true; buildConfig=true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    signingConfigs {
        create("codemagicTest") {
            if (ciSigningConfigured) {
                storeFile = file(ciKeystorePath!!)
                storePassword = ciKeystorePassword
                keyAlias = ciKeyAlias
                keyPassword = ciKeyPassword
            }
        }
        create("release") {
            if (ciSigningConfigured) {
                storeFile = file(ciKeystorePath!!)
                storePassword = ciKeystorePassword
                keyAlias = ciKeyAlias
                keyPassword = ciKeyPassword
            }
        }
    }
    buildTypes {
        getByName("debug") {
            if (ciSigningConfigured) {
                signingConfig = signingConfigs.getByName("codemagicTest")
            }
        }
        getByName("release") {
            if (ciSigningConfigured) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.11.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.11.1")
    implementation("androidx.media3:media3-ui:1.11.1")
    implementation("androidx.media3:media3-ui-compose:1.11.1")
    implementation("androidx.media3:media3-common:1.11.1")
    implementation("androidx.media3:media3-effect:1.11.1")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
}