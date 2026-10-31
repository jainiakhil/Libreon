plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "dev.offlineoffice.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.offlineoffice.app"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-m0"
    }
    buildTypes {
        debug {
            // Lets parallel work install side by side on one device: ./gradlew -PidSuffix=.pdf ...
            providers.gradleProperty("idSuffix").orNull?.let { applicationIdSuffix = it }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin { jvmToolchain(17) }
    buildFeatures { compose = true }
    packaging { jniLibs { useLegacyPackaging = false } }
}

ksp { arg("room.schemaLocation", "$projectDir/schemas") }

dependencies {
    implementation(project(":engine-pdf"))
    implementation(project(":engine-office"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.compose.material.icons)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.commonmark)
    implementation(libs.commonmark.ext.gfm.tables)
    implementation(libs.commonmark.ext.gfm.strikethrough)
    implementation(libs.commonmark.ext.task.list.items)
    testImplementation(libs.junit)
    // The platform org.json is a stub on the JVM; tests need a real implementation.
    testImplementation(libs.org.json)
}

// The app must never declare a permission. Fail the build if any dependency merges one in.
// Only AndroidX's app-private receiver permission (held by this app alone) is tolerated.
androidComponents {
    onVariants { variant ->
        val name = variant.name.replaceFirstChar { it.uppercase() }
        val manifest = variant.artifacts.get(com.android.build.api.artifact.SingleArtifact.MERGED_MANIFEST)
        val check = tasks.register("check${name}NoPermissions") {
            inputs.file(manifest)
            doLast {
                val text = manifest.get().asFile.readText()
                val perms = Regex("<uses-permission[^>]*android:name=\"([^\"]+)\"").findAll(text).map { it.groupValues[1] }
                    .filterNot { it == "${variant.applicationId.get()}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION" }
                    .toList()
                if (perms.isNotEmpty()) throw GradleException("Merged manifest declares permissions: $perms")
            }
        }
        tasks.matching { it.name == "assemble$name" }.configureEach { dependsOn(check) }
    }
}
