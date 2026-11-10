plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Ключ подписи общий с «Свинофермой»; пароль не хранится в проекте, а лежит рядом с ключом.
val keyDir = file("G:/posudomoyka-android")
val keystorePassword = File(keyDir, "keystore-password.txt").takeIf { it.exists() }?.readText()?.trim()

android {
    namespace = "io.github.fggtf24cyber.yaytsacloud"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.fggtf24cyber.yaytsacloud"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        create("release") {
            storeFile = File(keyDir, "android.keystore")
            storePassword = keystorePassword
            keyAlias = "posudomoyka"
            keyPassword = keystorePassword
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
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.work:work-runtime-ktx:2.10.1")
}
