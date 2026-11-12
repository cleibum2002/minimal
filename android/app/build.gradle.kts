plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.angel.minimal"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.angel.minimal"
        minSdk = 33 // Android 13+; the Redmi 14C runs Android 14
        targetSdk = 36
        versionCode = 17
        versionName = "0.9.2"
    }

    buildTypes {
        // the build you install: Android runs it much faster than a debug build
        release {
            isMinifyEnabled = false // keeps crash reports readable
            signingConfig = signingConfigs.getByName("debug") // same key as earlier builds, so it installs over them
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
