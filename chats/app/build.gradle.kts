import java.util.Properties

plugins {
    id("com.android.application")
}

// Upload-key signing config — the same upload key as the Garage app (repository secret UPLOAD_KEYSTORE_BUNDLE).
// Google Play App Signing keeps the real app signing key of shop.senfineco.chats.
val keystoreProps = Properties().apply {
    val f = rootProject.file("app/keystore/keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

// Version code: CI passes the run number of the "Build Senfineco Chats" workflow.
val ciVersionCode = (System.getenv("VERSION_CODE") ?: "1").toInt()
val ciVersionName = System.getenv("VERSION_NAME") ?: "1.0.$ciVersionCode"

android {
    namespace = "shop.senfineco.chats"
    compileSdk = 36

    defaultConfig {
        // Same id as the iPhone app «محادثات سنفينكو» (bundle id shop.senfineco.chats)
        applicationId = "shop.senfineco.chats"
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
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    bundle {
        language { enableSplit = false }
    }
}

dependencies {
    // Aligns kotlin-stdlib versions pulled in by androidx (avoids "Duplicate class kotlin...")
    implementation(platform("org.jetbrains.kotlin:kotlin-bom:1.9.24"))
    // Trusted Web Activity: the staff site runs full screen in Chrome (web push notifications, microphone, files)
    implementation("com.google.androidbrowserhelper:androidbrowserhelper:2.5.0")
}
