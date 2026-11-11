plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.typefree.ime"
    compileSdk = 35
    ndkVersion = "26.1.10909125"

    val usePrivateDict = project.findProperty("usePrivateDict") == "true"

    defaultConfig {
        applicationId = "com.typefree.ime"
        minSdk = 26
        targetSdk = 35
        versionCode = 18
        versionName = "3.1.0"
        buildConfigField("boolean", "USE_PRIVATE_DICT", usePrivateDict.toString())

        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    val privateDictDir = rootProject.file("local_private")
    val stagedPrivateAssetsDir = layout.buildDirectory.dir("generated/privateDictAssets")

    if (usePrivateDict && privateDictDir.exists()) {
        val stagePrivateFlypyAssets = tasks.register<Sync>("stagePrivateFlypyAssets") {
            from("src/main/assets") {
                include("rime/**")
            }
            from(privateDictDir) {
                include("flypy_dict.tsv", "flypy_dz.tsv")
            }
            into(stagedPrivateAssetsDir)
        }
        tasks.named("preBuild") {
            dependsOn(stagePrivateFlypyAssets)
        }
    }

    sourceSets {
        getByName("main") {
            if (usePrivateDict && privateDictDir.exists()) {
                assets.setSrcDirs(listOf(stagedPrivateAssetsDir))
            }
        }
    }
}

dependencies {
    implementation(project(":rime"))

    val composeBom = platform("androidx.compose:compose-bom:2025.04.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.savedstate:savedstate-ktx:1.2.1")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.11.0")

    testImplementation("junit:junit:4.13.2")
}
