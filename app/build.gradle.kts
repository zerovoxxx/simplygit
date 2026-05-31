import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    alias(libs.plugins.detekt)
}

val releaseKeystorePropertiesFile = rootProject.file("key.properties")
val releaseKeystoreProperties = Properties().apply {
    if (releaseKeystorePropertiesFile.exists()) {
        releaseKeystorePropertiesFile.inputStream().use { load(it) }
    }
}
val hasReleaseSigning = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
    .all { !releaseKeystoreProperties.getProperty(it).isNullOrBlank() }

android {
    namespace = "com.example.simplygit"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.simplygit"
        minSdk = 26
        targetSdk = 36
        versionCode = 6
        versionName = "1.0.6"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    splits {
        abi {
            isEnable = true
            reset()
            // Keep arm64 for release devices, and include x86_64 so Android
            // Studio can install debug builds on the bundled emulator images.
            include("arm64-v8a", "x86_64")
            isUniversalApk = false
        }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(releaseKeystoreProperties.getProperty("storeFile"))
                storePassword = releaseKeystoreProperties.getProperty("storePassword")
                keyAlias = releaseKeystoreProperties.getProperty("keyAlias")
                keyPassword = releaseKeystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
        // SPEC §4.1.1 / R-3: JGit 6.x relies on java.time / java.nio.file; must be paired with coreLibraryDesugaring(...) below.
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
    }
    // SPEC §4.1.1: JGit transitive META-INF resource collisions.
    packaging {
        resources.excludes += setOf(
            "META-INF/INDEX.LIST",
            "META-INF/io.netty.versions.properties",
            "META-INF/DEPENDENCIES",
            "META-INF/LICENSE.md",
            "META-INF/NOTICE.md",
            // SPEC §4.7 Iteration 3: jgit-ssh-apache packages its own OSGi
            // l10n properties that collide with the jgit core jar.
            "OSGI-INF/l10n/plugin.properties",
        )
    }
}

// SPEC NF5: detekt baseline + config. Baseline freezes legacy findings so new code is held to
// the full rule set while the backlog is paid off incrementally.
detekt {
    toolVersion = "1.23.6"
    config.setFrom(files("$rootDir/config/detekt/detekt.yml"))
    baseline = file("$rootDir/config/detekt/detekt-baseline.xml")
    buildUponDefaultConfig = true
    autoCorrect = false
}

tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
    jvmTarget = "11"
    reports {
        html.required.set(true)
        xml.required.set(true)
        sarif.required.set(false)
        md.required.set(false)
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    // SPEC §4.1.1 (Iteration 2): @HiltWorker bridge for WorkManager.
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    // Lifecycle / Coroutines
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)

    // Storage
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.security.crypto)

    // SPEC §4.1.1 (Iteration 2): Room for sync_policy / sync_log / repository persistence.
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // SPEC §4.1.1 (Iteration 2): WorkManager for the periodic sync engine.
    implementation(libs.androidx.work.runtime.ktx)

    // SPEC §4.1.1 (Iteration 2): NavHost for policy / audit screens.
    implementation(libs.androidx.navigation.compose)

    // JGit — exclude bcprov (AOSP-bundled clash) and jsch (SSH via Apache MINA SSHD).
    implementation(libs.jgit) {
        exclude(group = "org.bouncycastle")
        exclude(group = "com.jcraft", module = "jsch")
    }
    // SPEC §4.7 Iteration 3: Apache MINA SSHD transport for JGit SSH auth.
    // Pinned to the same jgit version to avoid ABI drift between core + ssh.apache.
    implementation(libs.jgit.ssh.apache) {
        exclude(group = "org.bouncycastle")
    }

    // Desugar (pairs with compileOptions.isCoreLibraryDesugaringEnabled).
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    // SPEC NF5: detekt baseline. First-party rules only; baseline suppresses legacy warnings.
    detektPlugins("io.gitlab.arturbosch.detekt:detekt-formatting:1.23.6")

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.robolectric)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    // SPEC §4.1.1 (Iteration 2): WorkManager + Room instrumentation helpers.
    androidTestImplementation(libs.androidx.work.testing)
    androidTestImplementation(libs.androidx.room.testing)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
