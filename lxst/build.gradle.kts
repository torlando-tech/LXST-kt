plugins {
    id("com.android.library")
    `maven-publish`
}

group = "com.github.torlando-tech.LXST-kt"
version = System.getenv("VERSION")?.removePrefix("v") ?: "0.1.0-SNAPSHOT"

android {
    namespace = "tech.torlando.lxst"
    compileSdk = 36

    buildFeatures {
        prefab = true
    }

    defaultConfig {
        minSdk = 24
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                arguments += "-DANDROID_STL=c++_shared"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
            isIncludeAndroidResources = true
        }
    }

    publishing {
        singleVariant("release") { withSourcesJar() }
    }
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                pom {
                    name.set("LXST-kt")
                    description.set("LXST telephony stack: Oboe audio pipeline, codec2/opus, Reticulum link transport.")
                    url.set("https://github.com/torlando-tech/LXST-kt")
                    licenses {
                        license {
                            name.set("Mozilla Public License 2.0")
                            url.set("https://www.mozilla.org/en-US/MPL/2.0/")
                        }
                    }
                    scm {
                        connection.set("scm:git:https://github.com/torlando-tech/LXST-kt.git")
                        developerConnection.set("scm:git:git@github.com:torlando-tech/LXST-kt.git")
                        url.set("https://github.com/torlando-tech/LXST-kt")
                    }
                }
            }
        }
    }
}

dependencies {
    // Native audio (Oboe C++ via Prefab)
    implementation("com.google.oboe:oboe:1.9.0")

    // Coroutines
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.mockk)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.robolectric)
    testImplementation(libs.test.core)
    testImplementation("org.json:json:20240303")

    // Instrumented testing (androidTest)
    androidTestImplementation(libs.junit.android)
    androidTestImplementation(libs.test.core)
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
}
