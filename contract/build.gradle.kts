import org.jetbrains.kotlin.config.KotlinCompilerVersion
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmOptions // Import KotlinJvmOptions

plugins {
    id("com.android.library")
    kotlin("android")
}

android {
    compileSdk = 36
    namespace = "com.andrerinas.openheadunit.contract"

    defaultConfig {
        minSdk = 16
        targetSdk = 34
    }

//    buildTypes {
//        create("release") {
//            postprocessing {
//                removeUnusedCode = false
//                removeUnusedResources = false
//                obfuscate = false
//                optimizeCode = false
//                proguardFile("proguard-rules.pro")
//            }
//        }
//    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        (this as KotlinJvmOptions).let {
           it.jvmTarget = "1.8"
        }
    }
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8:${KotlinCompilerVersion.VERSION}")
}
