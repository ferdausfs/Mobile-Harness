import java.util.Properties
import java.security.KeyStore
import java.security.MessageDigest
import org.gradle.api.tasks.Sync

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val testSecrets = Properties().apply {
    val secretsFile = rootProject.file("test-secrets.properties")
    if (secretsFile.isFile) secretsFile.inputStream().use(::load)
}
/** Set from the buildTypes block below; read by releaseSafetyCheck (script-scope hand-off). */
var releaseMinifyEnabled = true
val playBuild = providers.gradleProperty("playBuild").orNull?.toBoolean() == true ||
    providers.gradleProperty("playFeasibility").orNull?.toBoolean() == true
val privacyPolicyUrl = providers.gradleProperty("privacyPolicyUrl").orNull
    ?: "https://github.com/ferdausfs/Mobile-Harness/blob/main/PRIVACY.md"
val uploadStorePath = providers.environmentVariable("MH_UPLOAD_STORE_FILE").orNull
val uploadStorePassword = providers.environmentVariable("MH_UPLOAD_STORE_PASSWORD").orNull
val uploadKeyAlias = providers.environmentVariable("MH_UPLOAD_KEY_ALIAS").orNull
val uploadKeyPassword = providers.environmentVariable("MH_UPLOAD_KEY_PASSWORD").orNull
val hasUploadSigning = listOf(
    uploadStorePath,
    uploadStorePassword,
    uploadKeyAlias,
    uploadKeyPassword,
).all { !it.isNullOrBlank() }
val runtimeReleaseBaseUrl =
    "https://github.com/ferdausfs/Mobile-Harness/releases/download/runtime-2026.09.4"
val appUpdateManifestUrl =
    "https://github.com/ferdausfs/Mobile-Harness/releases/latest/download/mobile-harness-update.json"
val runtimeBundleDir = rootProject.layout.projectDirectory.dir("dist/runtime-bundles")
val generatedRuntimeAssets = layout.buildDirectory.dir("generated/runtime-assets")

/**
 * Install-test builds only (-PmhSkipAgyAsset=true): produce the real app
 * WITHOUT the 41 MB AGY runtime asset. Used to bisect on-device install
 * failures (does size/asset layout matter?). Never set for a release build:
 * the produced APK installs fine but Antigravity cannot install its runtime
 * (the embedded asset has no online fallback). The produced APK marks itself
 * via its versionName override (-PappVersionName=…-noagy-test).
 */
val skipAgyAsset = providers.gradleProperty("mhSkipAgyAsset").orNull?.toBoolean() == true

val prepareBundledAgentAssets = tasks.register<Sync>("prepareBundledAgentAssets") {
    if (!skipAgyAsset) {
        from(runtimeBundleDir.file("pocketdev-agy-arm64-2026.09.1.tar.zst"))
    }
    into(generatedRuntimeAssets.map { it.dir("shared/runtime") })
}

// Fail the build fast if the Antigravity runtime asset is missing from the
// staging directory. The `prepareBundledAgentAssets` Sync task silently copies
// nothing when the source file is absent, which produces an APK that crashes
// at install time on Antigravity (the embedded path has no online fallback).
// See audit finding F-02: the AGY bundle is gitignored and must be supplied
// separately; this guard makes a missing bundle a build error instead of a
// runtime error.
val ensureBundledAgentAssetsPresent = tasks.register("ensureBundledAgentAssetsPresent") {
    doLast {
        if (skipAgyAsset) {
            println("mhSkipAgyAsset=true: install-test build, AGY runtime asset intentionally excluded")
            return@doLast
        }
        val agyBundle = runtimeBundleDir.file("pocketdev-agy-arm64-2026.09.1.tar.zst").asFile
        check(agyBundle.isFile) {
            """
            Missing required runtime asset: ${agyBundle.relativeTo(rootDir)}

            The Antigravity runtime bundle 'pocketdev-agy-arm64-2026.09.1.tar.zst'
            is gitignored and must be supplied separately before building any
            flavor. Antigravity installation is forced to use the embedded asset
            (RuntimeInstaller.installRuntimeOverlay with forceEmbedded = true),
            so an APK built without this file will install cleanly and then fail
            at the first Antigravity install with a generic IOException.

            Obtain it from the runtime release:
              curl -L -o dist/runtime-bundles/pocketdev-agy-arm64-2026.09.1.tar.zst \
                https://github.com/ferdausfs/Mobile-Harness/releases/download/runtime-2026.09.4/pocketdev-agy-arm64-2026.09.1.tar.zst

            Verify against dist/runtime-bundles/manifest.json (sha256
            a659ab9188956fc4721ca86fb21b5118e0e489f47a5e02ae6b4f2fb423659d78)
            before rebuilding.
            """.trimIndent()
        }
    }
}

val prepareOfflineRuntimeAssets = tasks.register<Sync>("prepareOfflineRuntimeAssets") {
    from(
        runtimeBundleDir.file("pocketdev-core-arm64-2026.09.5.tar.zst"),
        runtimeBundleDir.file("pocketdev-claude-arm64-2026.09.1.tar.zst"),
        runtimeBundleDir.file("pocketdev-python-arm64-2026.09.2.tar.zst"),
        runtimeBundleDir.file("pocketdev-android-arm64-2026.09.1.tar.zst"),
        runtimeBundleDir.file("pocketdev-dsh-arm64-2026.09.1.tar.zst"),
    )
    into(generatedRuntimeAssets.map { it.dir("offline/runtime") })
}

// The offline flavor requires the additional 5 runtime bundles to be staged
// too. Same fail-fast contract as the AGY guard above.
val ensureOfflineRuntimeAssetsPresent = tasks.register("ensureOfflineRuntimeAssetsPresent") {
    doLast {
        val missing = listOf(
            "pocketdev-core-arm64-2026.09.5.tar.zst",
            "pocketdev-claude-arm64-2026.09.1.tar.zst",
            "pocketdev-python-arm64-2026.09.2.tar.zst",
            "pocketdev-android-arm64-2026.09.1.tar.zst",
            "pocketdev-dsh-arm64-2026.09.1.tar.zst",
        ).mapNotNull { name ->
            val f = runtimeBundleDir.file(name).asFile
            if (!f.isFile) name else null
        }
        check(missing.isEmpty()) {
            """
            Missing required offline runtime asset(s) under ${runtimeBundleDir.asFile.relativeTo(rootDir)}/:
              ${missing.joinToString("\n              ")}

            All five offline bundles are gitignored and must be supplied
            separately before building the offline flavor. Obtain them from
            the runtime-2026.09.4 GitHub release and verify each sha256
            against dist/runtime-bundles/manifest.json before rebuilding.
            """.trimIndent()
        }
    }
}

fun buildConfigString(value: String): String =
    "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

android {
    namespace = "com.jarves.mh"
    compileSdk = 36
    // F-Droid's r26b recipe installs 26.1.10909125. Keep AGP from selecting
    // its newer default NDK; local developers may override this explicitly.
    ndkVersion = providers.gradleProperty("mhNdkVersion").orNull ?: "26.1.10909125"

    signingConfigs {
        if (hasUploadSigning) {
            create("upload") {
                storeFile = rootProject.file(checkNotNull(uploadStorePath))
                storePassword = checkNotNull(uploadStorePassword)
                keyAlias = checkNotNull(uploadKeyAlias)
                keyPassword = checkNotNull(uploadKeyPassword)
            }
        }
    }

    defaultConfig {
        applicationId = "com.jarves.mh"
        minSdk = 28
        // The direct APK retains the proven target-28 PRoot execution path. The
        // Play build targets current Android while its runtime path is validated.
        targetSdk = if (playBuild) 36 else 28
        // Keep literal defaults so F-Droid's static manifest parser can detect
        // the tagged release. Gradle properties may still override Play builds.
        versionCode = 34
        versionName = "1.0.33"
        providers.gradleProperty("appVersionCode").orNull?.toIntOrNull()?.let { versionCode = it }
        providers.gradleProperty("appVersionName").orNull?.let { versionName = it }

        ndk.abiFilters += "arm64-v8a"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true

        buildConfigField("boolean", "IS_PLAY_BUILD", playBuild.toString())
        buildConfigField("String", "PRIVACY_POLICY_URL", buildConfigString(privacyPolicyUrl))

        buildConfigField(
            "String",
            "TEST_OPENROUTER_API_KEY",
            "\"\"",
        )
    }

    flavorDimensions += "runtimeDelivery"
    productFlavors {
        create("online") {
            dimension = "runtimeDelivery"
            buildConfigField("boolean", "OFFLINE_RUNTIME_BUNDLES", "false")
            buildConfigField("String", "RUNTIME_RELEASE_BASE_URL", buildConfigString(runtimeReleaseBaseUrl))
            buildConfigField("String", "APP_UPDATE_MANIFEST_URL", buildConfigString(appUpdateManifestUrl))
            buildConfigField("String", "APP_VARIANT", "\"online\"")
        }
        create("offline") {
            dimension = "runtimeDelivery"
            buildConfigField("boolean", "OFFLINE_RUNTIME_BUNDLES", "true")
            buildConfigField("String", "RUNTIME_RELEASE_BASE_URL", buildConfigString(runtimeReleaseBaseUrl))
            buildConfigField("String", "APP_UPDATE_MANIFEST_URL", buildConfigString(appUpdateManifestUrl))
            buildConfigField("String", "APP_VARIANT", "\"offline\"")
        }
    }

    sourceSets.getByName("offline").assets.srcDir(generatedRuntimeAssets.map { it.dir("offline") })
    sourceSets.getByName("main").assets.srcDir(generatedRuntimeAssets.map { it.dir("shared") })

    buildTypes {
        debug {
            buildConfigField(
                "String",
                "TEST_OPENROUTER_API_KEY",
                buildConfigString(testSecrets.getProperty("openrouter.apiKey", "")),
            )
        }
        release {
            isMinifyEnabled = false
            releaseMinifyEnabled = isMinifyEnabled
            if (hasUploadSigning) {
                signingConfig = signingConfigs.getByName("upload")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions.jvmTarget = "17"
    buildFeatures {
        compose = true
        buildConfig = true
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    packaging.resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    packaging.jniLibs.useLegacyPackaging = true
    androidResources.noCompress += "zst"
}

prepareBundledAgentAssets.configure { dependsOn(ensureBundledAgentAssetsPresent) }
prepareOfflineRuntimeAssets.configure { dependsOn(ensureOfflineRuntimeAssetsPresent) }

tasks.matching { it.name.startsWith("mergeOffline") && it.name.endsWith("Assets") }
    .configureEach { dependsOn(prepareOfflineRuntimeAssets) }

tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }
    .configureEach { dependsOn(prepareBundledAgentAssets) }

tasks.matching { it.name.contains("lint", ignoreCase = true) }
    .configureEach { dependsOn(prepareBundledAgentAssets) }

tasks.matching { it.name.contains("Offline") && it.name.contains("lint", ignoreCase = true) }
    .configureEach { dependsOn(prepareOfflineRuntimeAssets) }

// v1.0.31 release invariants (the "startup crash after update" report):
//   1. NO R8 minification on the release build — the whole codebase relies on
//      reflection-free but shrink-unsafe patterns (org.json + Kotlin defaults);
//      enabling minify without a keep-rule audit is a release-only crash class.
//   2. The signing cert must still be the never-rotate release cert
//      (d07ba804…dccfe) — a rotated cert silently bricks every update path.
// Runs automatically after :app:assembleOnlineRelease.
val expectedReleaseCertSha256 = "d07ba804cfa95dd39083002628b65487d7ae99acab12cdfa61c5f42bca7dccfe"

tasks.register("releaseSafetyCheck") {
    group = "verification"
    description = "Fails the release build when release invariants (no minify, never-rotate cert) are violated."
    doLast {
        check(!releaseMinifyEnabled) {
            "release build type has minification enabled WITHOUT a keep-rule audit. " +
                "The v1.0.31 hotfix analysis relies on isMinifyEnabled=false; enable it only with a full " +
                "proguard audit (org.json models, vaults, agent bridges) and AGENT_LOG documentation."
        }
        val storePath = providers.environmentVariable("MH_UPLOAD_STORE_FILE").orNull
        val storePass = providers.environmentVariable("MH_UPLOAD_STORE_PASSWORD").orNull
        if (!storePath.isNullOrBlank() && !storePass.isNullOrBlank() && file(storePath).isFile) {
            val keystoreFile = file(storePath)
            val loaded = runCatching {
                val ks = run {
                    val jks = KeyStore.getInstance("JKS")
                    try {
                        keystoreFile.inputStream().use { jks.load(it, storePass.toCharArray()) }
                        jks
                    } catch (notJks: Exception) {
                        val pkcs12 = KeyStore.getInstance("PKCS12")
                        keystoreFile.inputStream().use { pkcs12.load(it, storePass.toCharArray()) }
                        pkcs12
                    }
                }
                val alias = ks.aliases().nextElement()
                ks.getCertificate(alias)
            }.getOrNull()
            checkNotNull(loaded) { "Could not load the release keystore at $storePath" }
            val sha256 = MessageDigest.getInstance("SHA-256")
                .digest(loaded.encoded)
                .joinToString("") { byte -> "%02x".format(byte) }
            check(sha256.equals(expectedReleaseCertSha256, ignoreCase = true)) {
                "RELEASE CERT ROTATED: $sha256 does not match the never-rotate cert " +
                    "$expectedReleaseCertSha256 (d07ba804…dccfe). Existing installs can never update over this."
            }
            println("releaseSafetyCheck: cert OK (d07ba804…dccfe), minify off")
        } else {
            println("releaseSafetyCheck: minify off; signing env not set — cert check skipped (unsigned/other pipeline)")
        }
    }
}

tasks.matching { it.name == "assembleOnlineRelease" }.configureEach {
    finalizedBy("releaseSafetyCheck")
}

tasks.register("playReadinessCheck") {
    group = "verification"
    description = "Checks configuration required before uploading a Mobile Harness Play bundle."
    doLast {
        check(playBuild) { "Run with -PplayBuild=true." }
        check(privacyPolicyUrl.startsWith("https://")) {
            "privacyPolicyUrl must be a public HTTPS URL."
        }
        check(hasUploadSigning) {
            "Set MH_UPLOAD_STORE_FILE, MH_UPLOAD_STORE_PASSWORD, MH_UPLOAD_KEY_ALIAS, and MH_UPLOAD_KEY_PASSWORD."
        }
    }
}

// Fail the build instead of silently producing an unsigned release APK that
// existing installs can never update over. Checked at execution time so
// debug builds and unit tests do not require the signing secrets.
tasks.matching { task ->
    (task.name.startsWith("package") || task.name.startsWith("bundle")) && task.name.endsWith("Release")
}.configureEach {
    doFirst {
        check(hasUploadSigning) {
            "Release output would be unsigned. Export MH_UPLOAD_STORE_FILE, MH_UPLOAD_STORE_PASSWORD, MH_UPLOAD_KEY_ALIAS, and MH_UPLOAD_KEY_PASSWORD pointing at the release keystore before building."
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.02.00"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.apache.commons:commons-compress:1.27.1")
    implementation("com.github.luben:zstd-jni:1.5.6-9@aar")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250107")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
