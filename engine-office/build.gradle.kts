plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "dev.offlineoffice.office"
    compileSdk = 36

    defaultConfig {
        minSdk = 31
        consumerProguardFiles("consumer-rules.pro")
    }
    // The engine's native libraries and assets, copied out of the upstream build by
    // native/lok/package-lok.sh (git-ignored; without them Office tabs report an error).
    sourceSets["main"].jniLibs.srcDir(rootDir.resolve("third_party/lok/jniLibs"))
    sourceSets["main"].assets.srcDir(rootDir.resolve("third_party/lok/assets"))
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin { jvmToolchain(17) }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
}
