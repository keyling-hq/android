plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.keyling.client"
    compileSdk = 37

    defaultConfig {
        minSdk = 31
    }
}
