plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.typefree.pro.localai.nativelib"
    compileSdk = 35
    ndkVersion = "28.0.13004108"

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")

        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON",
                    "-DBUILD_SHARED_LIBS=OFF",
                    "-DGGML_OPENMP=OFF",
                    "-DGGML_LLAMAFILE=OFF",
                    "-DGGML_NATIVE=OFF",
                    "-DGGML_BACKEND_DL=OFF",
                    "-DGGML_CPU_ALL_VARIANTS=OFF",
                    "-DGGML_CPU_ARM_ARCH=armv8.2-a+dotprod+fp16",
                    "-DLLAMA_BUILD_COMMON=ON",
                    "-DLLAMA_BUILD_TESTS=OFF",
                    "-DLLAMA_BUILD_EXAMPLES=OFF",
                    "-DLLAMA_BUILD_TOOLS=OFF",
                    "-DLLAMA_BUILD_SERVER=OFF",
                    "-DLLAMA_BUILD_APP=OFF",
                    "-DLLAMA_CURL=OFF",
                    "-DLLAMA_OPENSSL=OFF"
                )
                cppFlags += listOf("-std=c++17", "-O3", "-fexceptions", "-frtti")
                targets("typefree_slm_jni")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        debug {
            externalNativeBuild {
                cmake {
                    // Benchmark & test with full -O3 ggml optimization on real ARM64 devices
                    arguments += listOf("-DCMAKE_BUILD_TYPE=Release")
                    cFlags += listOf("-O3")
                }
            }
        }
        release {
            isMinifyEnabled = false
            externalNativeBuild {
                cmake {
                    // Prevent AGP RelWithDebInfo's trailing -O2 from overriding -O3 on ggml-cpu
                    arguments += listOf("-DCMAKE_BUILD_TYPE=Release")
                    cFlags += listOf("-O3")
                }
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
