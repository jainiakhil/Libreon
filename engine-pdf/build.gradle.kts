plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "dev.offlineoffice.pdf"
    compileSdk = 36
    ndkVersion = "28.2.13676358"

    defaultConfig {
        minSdk = 31
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DANDROID_STL=c++_static",
                    "-DPDFIUM_ROOT=${rootDir.resolve("third_party/pdfium").invariantSeparatorsPath}",
                )
            }
        }
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    // AGP does not package IMPORTED CMake libraries, so ship the prebuilt PDFium via jniLibs.
    sourceSets["main"].jniLibs.srcDir(layout.buildDirectory.dir("pdfium-jnilibs"))
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin { jvmToolchain(17) }
}

val stagePdfium by tasks.registering(Sync::class) {
    listOf("arm64-v8a", "x86_64").forEach { abi ->
        from(rootDir.resolve("third_party/pdfium/$abi/lib/libpdfium.so")) { into(abi) }
    }
    into(layout.buildDirectory.dir("pdfium-jnilibs"))
}
tasks.named("preBuild") { dependsOn(stagePdfium) }

dependencies {
    implementation(libs.androidx.core.ktx)
}
