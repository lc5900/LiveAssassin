plugins {
    alias(libs.plugins.android.application)
}

val supportedAbis = listOf("armeabi-v7a", "arm64-v8a")
val targetAbi = (findProperty("targetAbi") as String?)
    ?.trim()
    ?.takeIf { it.isNotEmpty() }

if (targetAbi != null && targetAbi !in supportedAbis) {
    throw GradleException(
        "Unsupported targetAbi='$targetAbi'. Supported ABIs: ${supportedAbis.joinToString()}"
    )
}

android {
    namespace = "com.lc5900.liveassassin"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.lc5900.liveassassin"
        minSdk = 24
        targetSdk = 33
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            val abiList = targetAbi?.let { listOf(it) } ?: supportedAbis
            abiFilters += abiList
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    splits {
        abi {
            isEnable = true
            reset()
            val abiList = targetAbi?.let { listOf(it) } ?: supportedAbis
            include(*abiList.toTypedArray())
            isUniversalApk = targetAbi == null
        }
    }
    bundle {
        abi {
            enableSplit = true
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")
    implementation("com.github.jiangdongguo:AndroidUSBCamera:3.2.7")
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
