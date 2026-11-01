import java.util.Properties

plugins {
    id("com.android.application")
}

// Release signing: signing.properties locally (gitignored), SIGNING_* env vars in CI.
val signCfg = Properties().apply {
    val f = rootProject.file("signing.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun sign(key: String): String? =
    System.getenv("SIGNING_" + key.uppercase())?.trim()?.takeIf { it.isNotEmpty() }
        ?: signCfg.getProperty(key)?.trim()?.takeIf { it.isNotEmpty() }

val storeFilePath = sign("store_file")

// CI passes -PversionCode=<run number>. Play rejects any versionCode it has already seen.
val versionCodeArg = providers.gradleProperty("versionCode").orNull?.toInt() ?: 1
val versionLabel = "0.5" // bump by hand for each phase

android {
    namespace = "com.aevrin.hearth"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.aevrin.hearth"
        minSdk = 33
        targetSdk = 36
        versionCode = versionCodeArg
        versionName = "$versionLabel.$versionCodeArg"
    }

    signingConfigs {
        if (storeFilePath != null) {
            create("upload") {
                storeFile = rootProject.file(storeFilePath)
                storePassword = sign("store_password")
                keyAlias = sign("key_alias")
                keyPassword = sign("key_password")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("upload")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // The only dependency: the app drawer's scrolling grid. Apache-2.0, F-Droid friendly.
    implementation("androidx.recyclerview:recyclerview:1.4.0")
}

// An unsigned bundle is useless to Play; fail early with a clear message instead.
val hasSigning = storeFilePath != null
tasks.matching { it.name == "bundleRelease" || it.name == "assembleRelease" }.configureEach {
    doFirst {
        if (!hasSigning) throw GradleException(
            "No upload key configured. Set SIGNING_* env vars or create signing.properties."
        )
    }
}
