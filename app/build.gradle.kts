plugins {
    id("com.android.application")
}

android {
    namespace = "com.mahmoud.teacherassistant"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mahmoud.teacherassistant"
        minSdk = 23
        targetSdk = 35
        versionCode = 5
        versionName = "1.5.0"
    }

  signingConfigs {
    create("release") {
        storeFile = file("${project.rootDir}/upload-keystore.jks")
        storePassword = providers.gradleProperty("KEYSTORE_PASSWORD").orNull
        keyAlias = providers.gradleProperty("KEY_ALIAS").orNull
        keyPassword = providers.gradleProperty("KEY_PASSWORD").orNull
    }
}

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        debug {
            buildConfigField("boolean", "ADS_TEST_MODE", "true")
        }

        release {
            isMinifyEnabled = false
            isShrinkResources = false
            buildConfigField("boolean", "ADS_TEST_MODE", "false")

            signingConfig = signingConfigs.getByName("release")

            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/DEPENDENCIES",
            "META-INF/LICENSE",
            "META-INF/LICENSE.txt",
            "META-INF/NOTICE",
            "META-INF/NOTICE.txt"
        )
    }
}

dependencies {
    implementation("androidx.core:core:1.15.0")
    implementation("com.google.android.gms:play-services-ads:25.4.0")
}
