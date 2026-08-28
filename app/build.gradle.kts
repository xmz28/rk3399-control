plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.example.rk3399_control"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.example.rk3399_control"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

configurations.configureEach {
    resolutionStrategy.force(
        "androidx.lifecycle:lifecycle-common-java8:2.9.4",
        "androidx.lifecycle:lifecycle-common-jvm:2.9.4",
        "androidx.lifecycle:lifecycle-livedata:2.9.4",
        "androidx.lifecycle:lifecycle-livedata-core:2.9.4",
        "androidx.lifecycle:lifecycle-livedata-core-ktx:2.9.4",
        "androidx.lifecycle:lifecycle-process:2.9.4",
        "androidx.lifecycle:lifecycle-runtime-android:2.9.4",
        "androidx.lifecycle:lifecycle-runtime-compose-android:2.9.4",
        "androidx.lifecycle:lifecycle-runtime-ktx-android:2.9.4",
        "androidx.lifecycle:lifecycle-viewmodel:2.9.4",
        "androidx.lifecycle:lifecycle-viewmodel-android:2.9.4",
        "androidx.lifecycle:lifecycle-viewmodel-ktx:2.9.4",
        "androidx.lifecycle:lifecycle-viewmodel-savedstate-android:2.9.4"
    )
}
