plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.anded.xmb"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.anded.xmb"
        // RuntimeShader (AGSL) появился в Android 13.
        minSdk = 33
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
    }

    // full — личная (кнопка Essential через спец. возможности, поворот экрана, свои значки);
    // store — для Google Play: без службы спец. возможностей, имя и идентификатор временные.
    flavorDimensions += "edition"
    productFlavors {
        create("full") {
            dimension = "edition"
        }
        create("store") {
            dimension = "edition"
            applicationIdSuffix = ".store"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // Прототип: release ради скорости R8/ART, подпись отладочным ключом.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}
