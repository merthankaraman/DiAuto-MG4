import org.jetbrains.kotlin.gradle.dsl.KotlinJvmOptions
import org.jetbrains.kotlin.config.KotlinCompilerVersion
import java.util.Properties
import java.io.FileInputStream

plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("kapt")
}

android {
    compileSdk = 36
    ndkVersion = "27.2.12479018"
    namespace = "com.andrerinas.openheadunit"

    buildFeatures {
        buildConfig = true
        aidl = true // needed for shizuku
    }

    externalNativeBuild {
        cmake {
            path("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    val copyRootAssets = tasks.register<Copy>("copyRootAssets") {
        from("${project.rootDir}/CHANGELOG.md", "${project.rootDir}/LICENSE")
        into("${project.layout.buildDirectory.get().asFile}/generated/assets/root")
    }

    // Scan available locales at configuration time and store as BuildConfig field
    val resDir = file("src/main/res")
    val availableLocales = resDir.listFiles { file ->
        file.isDirectory && file.name.startsWith("values-") &&
        // Filter out non-language qualifiers (night mode, screen size, etc.)
        !file.name.contains("night") &&
        !file.name.contains("land") &&
        !file.name.contains("port") &&
        !file.name.matches(Regex("values-[whsml]\\d+.*")) &&
        !file.name.matches(Regex("values-v\\d+")) &&
        // Check that it contains strings.xml (actual translation)
        file.resolve("strings.xml").exists()
    }?.map { dir ->
        // Extract locale code from directory name (e.g., "values-es" -> "es", "values-pt-rBR" -> "pt-rBR")
        dir.name.removePrefix("values-")
    }?.sorted() ?: emptyList()

    println("Detected available locales: $availableLocales")

    val localProps = Properties().apply {
        val f = rootProject.file("local.properties")
        if (f.exists()) load(FileInputStream(f))
    }
    fun propOrEnv(key: String): String =
        localProps.getProperty(key)
            ?: (project.findProperty(key) as? String)
            ?: System.getenv(key)
            ?: ""
    fun escapeBuildConfig(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"")
    fun buildConfigString(value: String): String = "\"${escapeBuildConfig(value)}\""

    sourceSets {
        getByName("main") {
            assets.srcDirs("${project.layout.buildDirectory.get().asFile}/generated/assets/root")
        }
    }

    tasks.withType<com.android.build.gradle.tasks.MergeSourceSetFolders>().configureEach {
        dependsOn(copyRootAssets)
    }

    tasks.configureEach {
        if (name.contains("lint", ignoreCase = true)) {
            dependsOn(copyRootAssets)
        }
    }

    defaultConfig {
        // MG4 fork — separate id from upstream DiAuto / stock AA hosts.
        applicationId = "com.drivehub.diauto.mg4"
        minSdk = 28
        targetSdk = 36
        versionCode = 28
        versionName = "1.9.9"
        setProperty("archivesBaseName", "${applicationId}_${versionName}")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        multiDexEnabled = true
        vectorDrawables.useSupportLibrary = true

        // Store available locales in BuildConfig for runtime access
        // This is scanned at build time from values-XX directories
        buildConfigField("String", "AVAILABLE_LOCALES", "\"${availableLocales.joinToString(",")}\"")

        buildConfigField("String", "TELEGRAM_BOT_TOKEN", "\"\"")
        buildConfigField("String", "TELEGRAM_CHAT_ID", "\"\"")

        // MG4 HU uses 64-bit userspace on this unit; ship arm64 only.
        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                cppFlags("")
            }
        }
    }

    buildTypes {
        getByName("debug") {
            // Standard credentials are available to local debug builds only.
            buildConfigField("String", "TELEGRAM_BOT_TOKEN", buildConfigString(propOrEnv("TELEGRAM_BOT_TOKEN")))
            buildConfigField("String", "TELEGRAM_CHAT_ID", buildConfigString(propOrEnv("TELEGRAM_CHAT_ID")))
        }
        getByName("release") {
            // Release upload is opt-in and uses separate, explicitly named credentials.
            buildConfigField(
                "String",
                "TELEGRAM_BOT_TOKEN",
                buildConfigString(propOrEnv("TELEGRAM_BOT_TOKEN_RELEASE"))
            )
            buildConfigField(
                "String",
                "TELEGRAM_CHAT_ID",
                buildConfigString(propOrEnv("TELEGRAM_CHAT_ID_RELEASE"))
            )
        }
    }

    // Only device flavors — names stay short: carDebug / phoneDebug.
    flavorDimensions += "device"
    productFlavors {
        // Platform-signed MG4 head unit (sharedUserId via src/car/AndroidManifest.xml).
        create("car") {
            dimension = "device"
        }
        // Normal phone / tablet desk testing — no system sharedUserId; use Demo battery.
        create("phone") {
            dimension = "device"
            applicationIdSuffix = ".phone"
            versionNameSuffix = "-phone"
        }
    }

    androidComponents {
        beforeVariants { variantBuilder ->
            // phoneRelease is the public MVP (no system UID); carRelease is platform-signed locally.
            variantBuilder.enable = variantBuilder.name in setOf(
                "carDebug", "phoneDebug", "carRelease", "phoneRelease"
            )
        }
    }

    signingConfigs {
        getByName("debug") {
            // storeFile = file("../keystore.jkc")
            // storePassword = property("HEADUNIT_KEYSTORE_PASSWORD") as String
            // keyAlias = property("HEADUNIT_KEYSTORE_ALIAS") as String
            // keyPassword = property("HEADUNIT_KEYSTORE_PASSWORD") as String
        }
        create("release") {
            val defaultStoreFile = when {
                rootProject.file("headunit-release-key.jks").exists() -> rootProject.file("headunit-release-key.jks")
                file("../headunit-release-key.jks").exists() -> file("../headunit-release-key.jks")
                else -> null
            }
            if (defaultStoreFile != null) {
                storeFile = defaultStoreFile
            }
            keyAlias = "headunit-revived"

            val keyfile = rootProject.file("key.properties")
            val signingPropsFile = rootProject.file("secrets.properties")

            if (keyfile.exists()) {
                val keyprops = Properties()
                keyprops.load(FileInputStream(keyfile))

                if (keyprops.containsKey("storeFile")) storeFile = file(keyprops.getProperty("storeFile"))
                if (keyprops.containsKey("storePassword")) storePassword = keyprops.getProperty("storePassword")
                if (keyprops.containsKey("keyAlias")) keyAlias = keyprops.getProperty("keyAlias")
                if (keyprops.containsKey("keyPassword")) keyPassword = keyprops.getProperty("keyPassword")
            } else if (signingPropsFile.exists()) {
                val props = Properties()
                props.load(FileInputStream(signingPropsFile))

                storePassword = props.getProperty("HEADUNIT_KEYSTORE_PASSWORD")
                keyPassword = props.getProperty("HEADUNIT_KEY_PASSWORD")
            } else {
                val envStorePass = System.getenv("HEADUNIT_KEYSTORE_PASSWORD") ?: (project.findProperty("HEADUNIT_KEYSTORE_PASSWORD") as? String)
                val envKeyPass = System.getenv("HEADUNIT_KEY_PASSWORD") ?: (project.findProperty("HEADUNIT_KEY_PASSWORD") as? String)
                if (envStorePass != null) storePassword = envStorePass
                if (envKeyPass != null) keyPassword = envKeyPass
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android.txt"),
                "proguard-project.txt"
            )

            val relConfig = signingConfigs.getByName("release")
            if (relConfig.storeFile != null && relConfig.storeFile!!.exists()) {
                signingConfig = relConfig
            }
        }

        getByName("debug") {
            versionNameSuffix = "-debug"
        }
    }

    packaging {
        resources {
            excludes += "META-INF/DEPENDENCIES"
            excludes += "META-INF/LICENSE"
            excludes += "META-INF/LICENSE.txt"
            excludes += "META-INF/license.txt"
            excludes += "META-INF/NOTICE"
            excludes += "META-INF/NOTICE.txt"
            excludes += "META-INF/notice.txt"
            excludes += "META-INF/ASL2.0"
            excludes += "META-INF/*.kotlin_module"
        }
    }

    lint {
        abortOnError = false
        disable += "PackagedPrivateKey"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        (this as KotlinJvmOptions).let {
            it.jvmTarget = "17"
        }
    }

    applicationVariants.all {
        val variant = this
        variant.outputs
            .map { it as com.android.build.gradle.internal.api.BaseVariantOutputImpl }
            .forEach { output ->
                var outputFileName = "${variant.applicationId}_${variant.versionName}_debug.apk"
                if(variant.buildType.name == "release") {
                    outputFileName = "${variant.applicationId}_${variant.versionName}.apk"
                    output.outputFileName = outputFileName
                }
                output.outputFileName = outputFileName
            }
    }
}

dependencies {
    implementation("org.conscrypt:conscrypt-android:2.5.3")

    implementation("com.google.protobuf:protobuf-java:3.25.9")
    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("androidx.fragment:fragment-ktx:1.6.2")
    implementation("androidx.media:media:1.6.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("com.google.android.material:material:1.10.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.startup:startup-runtime:1.1.1")
    implementation("com.google.android.gms:play-services-nearby:19.3.0")
    // ViewModel and LiveData
    kapt("androidx.lifecycle:lifecycle-compiler:2.6.2")

    // KTX
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.6.2")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("org.json:json:20240303")

    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8:${KotlinCompilerVersion.VERSION}")
    implementation(project(":contract"))
    implementation(project(":evhardware"))

    // Multidex
    implementation("androidx.multidex:multidex:2.0.1")

    // Navigation Component
    implementation("androidx.navigation:navigation-fragment-ktx:2.3.5")
    implementation("androidx.navigation:navigation-ui-ktx:2.3.5")

    // DexMaker for runtime subclassing (Hotspot Fix)
    implementation("com.linkedin.dexmaker:dexmaker:2.28.3")

    // Glide for image/GIF loading (custom loading screen)
    implementation("com.github.bumptech.glide:glide:4.16.0")
    kapt("com.github.bumptech.glide:compiler:4.16.0")

    // ZXing for QR Code generation
    implementation("com.google.zxing:core:3.5.3")

    // Shizuku for root / shell access
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation("com.github.topjohnwu.libsu:core:6.0.0")
}
