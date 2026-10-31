import java.util.Properties

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
        applicationId = "dev.libreon.app"
        minSdk = 31
        targetSdk = 36
        versionCode = 4
        versionName = "0.9.0-beta.4"
    }
    // The release key lives outside version control (signing/, git-ignored). Without it,
    // release builds fall back to the debug key, which is fine for local testing only.
    val releaseKey = rootDir.resolve("signing/keystore.properties").takeIf { it.isFile }?.let { file ->
        Properties().apply { file.inputStream().use { load(it) } }
    }
    signingConfigs {
        if (releaseKey != null) {
            create("release") {
                storeFile = rootDir.resolve(releaseKey.getProperty("storeFile"))
                storePassword = releaseKey.getProperty("storePassword")
                keyAlias = releaseKey.getProperty("keyAlias")
                keyPassword = releaseKey.getProperty("keyPassword")
            }
        }
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
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
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
    // Compressed native libraries: the engine is ~185 MB raw but ~55 MB compressed, so the
    // download is less than half the size. Android extracts them once at install.
    packaging { jniLibs { useLegacyPackaging = true } }
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
