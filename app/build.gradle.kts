plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val ks = file(System.getProperty("user.home") + "/ghadd.jks")

android {
    namespace = "com.ghadd.app"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.ghadd.app"
        minSdk = 24
        targetSdk = 34
        versionCode = 2
        versionName = "1.1"
        resourceConfigurations += listOf("ar", "en", "fr")
    }
    signingConfigs {
        create("release") {
            if (ks.exists()) {
                storeFile = ks; storePassword = "ghadd123"
                keyAlias = "ghadd"; keyPassword = "ghadd123"
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = if (ks.exists()) signingConfigs.getByName("release")
                            else signingConfigs.getByName("debug")
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
// بلا أي مكتبات خارجية: التطبيق يعتمد على إطار أندرويد فقط ليكون أخف ما يمكن
