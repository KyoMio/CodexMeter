import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.kmnexus.codexmeter"
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "com.kmnexus.codexmeter"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Antigravity OAuth client secret. Extracted from the official Antigravity
        // installed-app client — non-confidential per Google's installed-app model,
        // but kept out of source so it never lands in the public repo. CI injects it
        // via env; local builds fall back to local.properties (gitignored).
        val antigravityOauthClientSecret =
            System.getenv("ANTIGRAVITY_OAUTH_CLIENT_SECRET")
                ?: Properties().run {
                    val propsFile = rootProject.file("local.properties")
                    if (propsFile.exists()) propsFile.inputStream().use { load(it) }
                    getProperty("antigravity.oauth.clientSecret")
                }
                ?: ""
        buildConfigField(
            "String",
            "ANTIGRAVITY_OAUTH_CLIENT_SECRET",
            "\"$antigravityOauthClientSecret\"",
        )
    }

    signingConfigs {
        create("release") {
            // CI: all four env vars are injected by GitHub Actions.
            // Local: falls back to ~/.android/ properties file — behaviour unchanged.
            val envKeystorePath  = System.getenv("RELEASE_KEYSTORE_PATH")
            val envStorePassword = System.getenv("RELEASE_STORE_PASSWORD")
            val envKeyAlias      = System.getenv("RELEASE_KEY_ALIAS")
            val envKeyPassword   = System.getenv("RELEASE_KEY_PASSWORD")

            if (envKeystorePath != null && envStorePassword != null &&
                envKeyAlias != null && envKeyPassword != null) {
                storeFile     = file(envKeystorePath)
                storePassword = envStorePassword
                keyAlias      = envKeyAlias
                keyPassword   = envKeyPassword
            } else {
                val keystoreProps = Properties().also { props ->
                    val propsFile = file("${System.getProperty("user.home")}/.android/codexmeter-release-keystore.properties")
                    if (propsFile.exists()) props.load(propsFile.inputStream())
                }
                storeFile     = keystoreProps.getProperty("storeFile")?.let { file(it) }
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias      = keystoreProps.getProperty("keyAlias")
                keyPassword   = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            isDebuggable = true
        }
        release {
            isDebuggable = false
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.navigation.compose)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.datastore.preferences)
    implementation(libs.work.runtime.ktx)
    implementation(libs.glance.appwidget)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.qm.liquidglass.core)
    implementation("androidx.browser:browser:1.8.0")

    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.robolectric)
    testImplementation(libs.turbine)

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core-ktx:1.6.1")
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
