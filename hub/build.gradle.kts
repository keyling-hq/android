plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.wire)
}

android {
    namespace = "com.keyling.hub"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.keyling.hub"
        minSdk = 31
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures {
        compose = true
    }
}

check(file("../proto/bridge.proto").exists()) {
    "proto/ is empty: run `git submodule update --init` in the repository root"
}

wire {
    sourcePath {
        srcDir("../proto")
    }
    kotlin {}
}

dependencies {
    implementation(project(":client"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.activity.compose)
    implementation(libs.coroutines.android)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}
