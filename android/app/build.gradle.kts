import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val keystoreProperties = Properties()
val keystorePropertiesFile = rootProject.file("keystore.properties")
if (keystorePropertiesFile.isFile) {
    keystorePropertiesFile.inputStream().use { keystoreProperties.load(it) }
}

fun signingValue(propertyKey: String, envName: String): String? {
    return System.getenv(envName)?.takeIf { it.isNotBlank() }
        ?: keystoreProperties.getProperty(propertyKey)?.takeIf { it.isNotBlank() }
}

val broadcastifyProperties = Properties()
val broadcastifyPropertiesFile = rootProject.file("broadcastify.properties")
if (broadcastifyPropertiesFile.isFile) {
    broadcastifyPropertiesFile.inputStream().use { broadcastifyProperties.load(it) }
}

fun broadcastifyCredential(propertyKey: String, envName: String): String {
    return System.getenv(envName)?.takeIf { it.isNotBlank() }
        ?: broadcastifyProperties.getProperty(propertyKey)?.takeIf { it.isNotBlank() }
        ?: ""
}

/** Java string literal for BuildConfig. Does not log the value. */
fun javaString(value: String): String {
    val escaped = buildString {
        value.forEach { ch ->
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                else -> append(ch)
            }
        }
    }
    return "\"$escaped\""
}

val releaseStorePath = signingValue("storeFile", "RELEASE_STORE_FILE")
val releaseStorePassword = signingValue("storePassword", "RELEASE_STORE_PASSWORD")
val releaseKeyAlias = signingValue("keyAlias", "RELEASE_KEY_ALIAS")
val releaseKeyPassword = signingValue("keyPassword", "RELEASE_KEY_PASSWORD")
val releaseStoreFile = releaseStorePath?.let { path ->
    val raw = file(path)
    val resolved = if (raw.isAbsolute) raw else rootProject.file(path)
    resolved.takeIf { it.isFile }
}
val releaseSigningReady = releaseStoreFile != null &&
    !releaseStorePassword.isNullOrBlank() &&
    !releaseKeyAlias.isNullOrBlank() &&
    !releaseKeyPassword.isNullOrBlank()

android {
    namespace = "com.clintmaples.broadcastifyscanner"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.clintmaples.broadcastifyscanner"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "0.4.1"

        val broadcastifyUser = broadcastifyCredential(
            "broadcastify.username",
            "BROADCASTIFY_USERNAME",
        )
        val broadcastifyPassword = broadcastifyCredential(
            "broadcastify.password",
            "BROADCASTIFY_PASSWORD",
        )
        buildConfigField("String", "BROADCASTIFY_USERNAME", javaString(broadcastifyUser))
        buildConfigField("String", "BROADCASTIFY_PASSWORD", javaString(broadcastifyPassword))

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (releaseSigningReady) {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            // Never fall back to the Android Debug cert (A-01).
            // Unsigned if keystore.properties / RELEASE_* env are absent.
            if (releaseSigningReady) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=androidx.media3.common.util.UnstableApi",
        )
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    val media3 = "1.8.0"

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-datasource:$media3")
    implementation("androidx.media3:media3-datasource-okhttp:$media3")
    implementation("androidx.media3:media3-common:$media3")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}

tasks.configureEach {
    if (name == "assembleRelease" || name == "bundleRelease") {
        doFirst {
            val user = broadcastifyCredential("broadcastify.username", "BROADCASTIFY_USERNAME")
            val pass = broadcastifyCredential("broadcastify.password", "BROADCASTIFY_PASSWORD")
            if (user.isBlank() || pass.isBlank()) {
                throw GradleException(
                    "Release build needs BROADCASTIFY_USERNAME and BROADCASTIFY_PASSWORD, " +
                        "or android/broadcastify.properties. Do not commit that file.",
                )
            }
        }
    }
}
