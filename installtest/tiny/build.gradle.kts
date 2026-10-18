plugins {
    id("com.android.application")
}

// mh-installtest-tiny.apk — install bisection probe, NOT a release.
//
// Purpose: answer "does the phone accept ANY targetSdk-28, release-key-signed
// APK right now?" without the 88 MB real app in the picture. Same signing key
// as the release line (cert d07ba804…ccfe), same min/target SDK, but a
// different applicationId so it can never collide with com.jarves.mh.
//
// Build from the repo root:
//   MH_UPLOAD_STORE_FILE=... MH_UPLOAD_STORE_PASSWORD=... MH_UPLOAD_KEY_ALIAS=... MH_UPLOAD_KEY_PASSWORD=... \
//   ./gradlew -p installtest :tiny:assembleRelease
// Output: installtest/tiny/build/outputs/apk/release/tiny-release.apk

android {
    namespace = "com.jarves.mh.installtest"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.jarves.mh.installtest"
        minSdk = 28
        // Same targetSdk as the real app on purpose: the probe must match the
        // real app's SDK posture so a pass/fail transfers to it.
        targetSdk = 28
        versionCode = 1
        versionName = "tiny-installtest-1"
    }

    signingConfigs {
        val envStorePath = providers.environmentVariable("MH_UPLOAD_STORE_FILE").orNull
        val envStorePass = providers.environmentVariable("MH_UPLOAD_STORE_PASSWORD").orNull
        val envAlias = providers.environmentVariable("MH_UPLOAD_KEY_ALIAS").orNull
        val envKeyPass = providers.environmentVariable("MH_UPLOAD_KEY_PASSWORD").orNull
        if (listOf(envStorePath, envStorePass, envAlias, envKeyPass).all { !it.isNullOrBlank() }) {
            create("release") {
                storeFile = rootProject.file(checkNotNull(envStorePath))
                storePassword = checkNotNull(envStorePass)
                keyAlias = checkNotNull(envAlias)
                keyPassword = checkNotNull(envKeyPass)
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    // This is an install probe, not a Play submission: targetSdk 28 matches
    // the real app on purpose, so Play's ExpiredTargetSdkVersion lint must
    // not block assembling the probe.
    lint {
        checkReleaseBuilds = false
    }
}
