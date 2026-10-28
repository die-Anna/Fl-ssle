plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "at.hochwasser.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "at.hochwasser.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "0.5"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Für den Privatgebrauch mit dem Debug-Schlüssel signieren, damit die APK direkt installierbar ist
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
    packaging {
        resources.excludes += "META-INF/*"
    }
}

dependencies {
    implementation(project(":core"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("com.google.android.flexbox:flexbox:3.0.0")
    implementation("org.osmdroid:osmdroid-android:6.1.20")
}
