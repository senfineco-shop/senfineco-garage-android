import java.util.Properties

plugins {
    id("com.android.application")
}

// Upload-key signing config (Play App Signing keeps the real app signing key).
val keystoreProps = Properties().apply {
    val f = rootProject.file("app/keystore/keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

// Version code: CI passes the GitHub run number so every build is higher than the last.
val ciVersionCode = (System.getenv("VERSION_CODE") ?: "1").toInt()
val ciVersionName = System.getenv("VERSION_NAME") ?: "1.0.$ciVersionCode"

android {
    namespace = "shop.senfineco.garage"
    compileSdk = 36

    defaultConfig {
        applicationId = "shop.senfineco.garage"
        minSdk = 24
        targetSdk = 36
        versionCode = ciVersionCode
        versionName = ciVersionName
    }

    signingConfigs {
        create("upload") {
            storeFile = file(keystoreProps.getProperty("storeFile", "keystore/upload.jks"))
            storePassword = keystoreProps.getProperty("storePassword", "")
            keyAlias = keystoreProps.getProperty("keyAlias", "upload")
            keyPassword = keystoreProps.getProperty("keyPassword", "")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("upload")
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    bundle {
        language { enableSplit = false }
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
}
