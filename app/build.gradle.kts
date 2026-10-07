plugins {
    id("com.android.application")
}

android {
    namespace = "com.hotelatgangnam.smsbridge"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.hotelatgangnam.smsbridge"
        minSdk = 26
        targetSdk = 36
        versionCode = 20_000
        versionName = "2.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.all {
            it.useJUnit()
        }
    }
}

dependencies {
    // 5.5.0 requires compileSdk 37, which is not yet published in the stable SDK channel.
    implementation("com.squareup.okhttp3:okhttp:5.4.0")

    testImplementation("junit:junit:4.13.2")
}
