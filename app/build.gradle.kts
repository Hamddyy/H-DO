plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.hdo.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.hdo.app"
        minSdk = 29            // MediaStore-based saving needs no storage permission from Android 10
        targetSdk = 35
        versionCode = 20
        versionName = "1.5.7"
    }

    // Release signing comes from environment variables (GitHub Actions secrets). Without them the
    // release APK is left unsigned, so local builds never need the key.
    val ksFile = System.getenv("HDO_KEYSTORE_FILE")
    if (!ksFile.isNullOrBlank()) {
        signingConfigs {
            create("hdoRelease") {
                storeFile = file(ksFile)
                storePassword = System.getenv("HDO_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("HDO_KEY_ALIAS")
                keyPassword = System.getenv("HDO_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            if (!ksFile.isNullOrBlank()) signingConfig = signingConfigs.getByName("hdoRelease")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    lint { checkReleaseBuilds = false; abortOnError = false }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }

    // yt-dlp, python and ffmpeg are shipped as native libraries and must be extracted to disk
    packaging {
        jniLibs { useLegacyPackaging = true }
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES", "META-INF/LICENSE", "META-INF/LICENSE.txt", "META-INF/LICENSE.md",
                "META-INF/NOTICE", "META-INF/NOTICE.txt", "META-INF/ASL2.0", "META-INF/*.kotlin_module",
            )
        }
    }

    // one APK per CPU architecture keeps each file small (python + ffmpeg are large)
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = false
        }
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")
    val bom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(bom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // yt-dlp fallback engine (lazy-loaded) for sites NewPipe does not cover (Facebook etc.)
    implementation("io.github.junkfood02.youtubedl-android:library:0.18.1")
    // NewPipe Extractor: pure-Java/Kotlin YouTube+SoundCloud extractor (no Python, no yt-dlp)
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.26.5")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // yt-dlp + python + ffmpeg for Android (same engine used by Seal and YTDLnis)
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1")
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("androidx.media3:media3-session:1.4.1")
}
