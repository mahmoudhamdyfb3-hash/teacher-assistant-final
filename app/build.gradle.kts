plugins { id("com.android.application") }

android {
    namespace = "com.mahmoud.teacherassistant"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mahmoud.teacherassistant"
        minSdk = 23
        targetSdk = 35
        versionCode = 4
        versionName = "1.4.0"
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        debug {
            // Always use Google's test banner while developing to avoid invalid activity.
            buildConfigField("boolean", "ADS_TEST_MODE", "true")
        }
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            // Production release uses the real AdMob banner unit supplied for this app.
            buildConfigField("boolean", "ADS_TEST_MODE", "false")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    packaging {
        resources.excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE", "META-INF/LICENSE.txt", "META-INF/NOTICE", "META-INF/NOTICE.txt")
    }
}


dependencies {
    implementation("androidx.core:core:1.15.0")
    implementation("com.google.android.gms:play-services-ads:25.4.0")
}
