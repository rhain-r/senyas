plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.benten.senyas"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.benten.senyas"
        minSdk = 26
        targetSdk = 37
        versionCode = 7
        versionName = "1.5"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        create("connected") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".cameraqa"
            isDebuggable = true
            matchingFallbacks += listOf("debug")
        }
        create("connectedEmulator") {
            initWith(getByName("connected"))
            matchingFallbacks += listOf("debug")
        }
        release {
            optimization {
                enable = false
            }
        }
    }
    testBuildType = providers.gradleProperty("senyasTestBuildType").orElse("debug").get()
    sourceSets.getByName("connectedEmulator").assets.srcDir("src/connected/assets")
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    androidResources {
        noCompress += "task"
    }
}

dependencies {
    implementation(platform("com.google.firebase:firebase-bom:35.1.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-firestore")
    implementation(libs.activity.ktx)
    implementation(libs.appcompat)
    implementation(libs.constraintlayout)
    implementation(libs.material)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)
    implementation(libs.mediapipe.vision)
    testImplementation(libs.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.ext.junit)
}
