plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Version numbers come from CI so every pushed build is a strictly newer release.
val runNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

android {
    namespace = "com.jondude1.androidlm"
    compileSdk = 36
    ndkVersion = "29.0.13113456"

    defaultConfig {
        applicationId = "com.jondude1.androidlm"
        minSdk = 31
        targetSdk = 36
        versionCode = runNumber
        versionName = "1.0.$runNumber"

        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DCMAKE_BUILD_TYPE=Release",
                    "-DANDROID_STL=c++_shared",
                    "-DLM_THIRD_PARTY=${rootProject.file("third_party").absolutePath}",
                )
            }
        }
    }

    // One permanent key, committed on purpose (sideloaded app): updates must always be signed by the same key.
    signingConfigs {
        create("androidlm") {
            storeFile = rootProject.file("keystore/androidlm.jks")
            storePassword = "androidlm"
            keyAlias = "androidlm"
            keyPassword = "androidlm"
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("androidlm")
        }
        debug {
            signingConfig = signingConfigs.getByName("androidlm")
        }
    }

    externalNativeBuild {
        cmake {
            path("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }

    packaging {
        jniLibs {
            // ggml loads its backend .so files from nativeLibraryDir at runtime, so they must be extracted.
            useLegacyPackaging = true
            // Link-time stub of the Khronos ICD loader. At runtime the phone's own vendor libOpenCL.so must be used.
            excludes += "**/libOpenCL.so"
        }
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
}
