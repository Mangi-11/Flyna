plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "io.github.mangi.flyna"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "io.github.mangi.flyna"
        minSdk = 35
        targetSdk = 37
        //noinspection HighAppVersionCode
        versionCode = 2026102502
        versionName = "0.1.9"

        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        release {
            optimization {
                enable = true
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    packaging {
        dex {
            useLegacyPackaging = true
        }
        resources {
            merges += "META-INF/xposed/*"
        }
    }
}

androidComponents {
    onVariants(selector().all()) { variant ->
        val suffix = if (variant.buildType == "debug") "-Debug" else ""
        variant.outputs.forEach { output ->
            output.outputFileName.set(
                output.versionName.map { version -> "Flyna-v$version$suffix.apk" },
            )
        }
    }
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.lifecycle.runtime.compose)
    compileOnly(libs.libxposed.api)
    implementation(libs.dexkit)
    implementation(libs.libxposed.service)
    implementation(libs.miuix.blur)
    implementation(libs.miuix.nav)
    implementation(libs.miuix.preference)
    implementation(libs.miuix.ui)
    testImplementation(libs.junit)
}
