plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "com.zhuquan.scheduledfocus"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.zhuquan.scheduledfocus"
        minSdk = 26
        targetSdk = 35
        versionCode = providers.gradleProperty("focusVersionCode").orNull?.toInt() ?: 5
        versionName = providers.gradleProperty("focusVersionName").orNull ?: "0.3.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    signingConfigs {
        create("stable") {
            storeFile = file(providers.environmentVariable("FOCUS_KEYSTORE").orNull
                ?: "${System.getProperty("user.home")}/.codex/signing/scheduled-focus.jks")
            storePassword = providers.environmentVariable("FOCUS_STORE_PASSWORD").orNull ?: "android"
            keyAlias = providers.environmentVariable("FOCUS_KEY_ALIAS").orNull ?: "androiddebugkey"
            keyPassword = providers.environmentVariable("FOCUS_KEY_PASSWORD").orNull ?: "android"
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("stable")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions.jvmTarget = "17"
    buildFeatures { compose = true; buildConfig = true }
    packaging.resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
