import java.util.Properties

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.jetbrainsKotlinAndroid)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
}

val localProps = Properties()
val localPropsFile = rootProject.file("local.properties")
if (localPropsFile.exists()) {
    localProps.load(localPropsFile.inputStream())
}

android {
    namespace = "com.wallupclaw.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.wallupclaw.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 13
        versionName = "2.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        buildConfigField(
            "String",
            "GITHUB_RELEASES_TOKEN",
            "\"${localProps.getProperty("GITHUB_RELEASES_TOKEN", "")}\""
        )
    }

    signingConfigs {
        create("release") {
            val props = Properties()
            val propsFile = rootProject.file("local.properties")
            if (propsFile.exists()) props.load(propsFile.inputStream())

            storeFile = rootProject.file(props.getProperty("RELEASE_STORE_FILE", System.getenv("RELEASE_STORE_FILE") ?: "release.keystore"))
            storePassword = props.getProperty("RELEASE_STORE_PASSWORD") ?: System.getenv("RELEASE_STORE_PASSWORD") ?: ""
            keyAlias = props.getProperty("RELEASE_KEY_ALIAS") ?: System.getenv("RELEASE_KEY_ALIAS") ?: "wallup-release"
            keyPassword = props.getProperty("RELEASE_KEY_PASSWORD") ?: System.getenv("RELEASE_KEY_PASSWORD") ?: ""
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.15"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    lint {
        disable.add("NullSafeMutableLiveData")
    }
}

dependencies {

    // For local development with the LiveKit Compose SDK only.
    // implementation("io.livekit:livekit-compose-components")

    implementation(libs.livekit.lib)
    implementation(libs.livekit.components)

    implementation(libs.accompanist.permissions)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.constraintlayout.compose)
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.gson)
    implementation(libs.okhttp)
    implementation(libs.timberkt)

    // Wake word detection (ONNX Runtime)
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.17.0")

    // Settings persistence
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Emoji support (ensures emojis render on all devices)
    implementation("androidx.emoji2:emoji2:1.5.0")
    implementation("androidx.emoji2:emoji2-bundled:1.5.0")

    // DLNA/UPnP MediaRenderer — full UPnP stack (SSDP, HTTP, SOAP, eventing)
    implementation("org.jupnp:org.jupnp:3.0.4")
    implementation("org.jupnp:org.jupnp.support:3.0.4")
    implementation("org.jupnp:org.jupnp.android:3.0.3")
    // jUPnP requires Jetty as HTTP transport
    implementation("org.eclipse.jetty:jetty-server:9.4.56.v20240826")
    implementation("org.eclipse.jetty:jetty-servlet:9.4.56.v20240826")
    implementation("org.eclipse.jetty:jetty-client:9.4.56.v20240826")
    // SLF4J logging for jUPnP on Android
    implementation("org.slf4j:slf4j-android:1.7.36")

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}