import com.android.build.api.instrumentation.FramesComputationMode
import java.util.Properties
import com.android.build.api.instrumentation.InstrumentationScope

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Old Android lacks a few Java methods NewPipeExtractor calls; rewrite those calls at build time (see buildSrc).
androidComponents {
    onVariants { variant ->
        variant.instrumentation.transformClassesWith(CompatRewriteFactory::class.java, InstrumentationScope.ALL) {}
        variant.instrumentation.setAsmFramesComputationMode(FramesComputationMode.COPY_FRAMES)
    }
}

android {
    namespace = "app.kinora"
    compileSdk = 34

    defaultConfig {
        applicationId = "app.kinora"
        minSdk = 24
        targetSdk = 34
        // the release script passes these; plain debug builds keep 1 / "1.0"
        versionCode = (project.findProperty("versionCode") as String?)?.toInt() ?: 1
        versionName = (project.findProperty("versionName") as String?) ?: "1.0"
    }

    // Release signing: android/keystore.properties (made by build-release.ps1) points at android/release.keystore.
    // -PsignWithDebug=true signs the release build with the debug key instead, so it installs over a debug build in place.
    val keystoreProps = Properties().apply {
        val f = rootProject.file("keystore.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    signingConfigs {
        if (keystoreProps.isNotEmpty()) create("release") {
            storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
            storePassword = keystoreProps.getProperty("storePassword")
            keyAlias = keystoreProps.getProperty("keyAlias")
            keyPassword = keystoreProps.getProperty("keyPassword")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (project.hasProperty("signWithDebug")) signingConfigs.getByName("debug") else signingConfigs.findByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true // NewPipeExtractor uses java.time etc.
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    val media3 = "1.3.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3") // live streams are HLS playlists
    implementation("androidx.media3:media3-session:$media3")
    implementation("androidx.media3:media3-ui:$media3")

    implementation("io.coil-kt:coil-compose:2.6.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // On-device YouTube search / stream lookup (replaces the PC server by default).
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.27.0")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")

    // Network (SMB / Windows share) files: browse and play videos from a PC or NAS on the home network.
    implementation("eu.agno3.jcifs:jcifs-ng:2.1.10")
    implementation("org.slf4j:slf4j-api:1.7.36") // jcifs logs through it; with no binding it is silent
}
