plugins { id("com.android.application") }

android {
    namespace = "com.oasisfeng.island.sideplay"

    compileSdk = 35

    defaultConfig {
        minSdk = 31
        targetSdk = 31
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
        }
    }
}
