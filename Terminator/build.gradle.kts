plugins {
    alias(libs.plugins.android.library)
    id("maven-publish")
}

android {
    namespace = "com.skiy.terminator"
    compileSdk = 35

    defaultConfig {
        minSdk = 26

        testInstrumentationRunner = "android.support.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    publishing {
        singleVariant("release")
    }
}

dependencies {

    api("com.github.vova7878.PanamaPort:AndroidPanama:9ef0a014f1")
    api("com.github.vova7878.PanamaPort:AndroidUnsafe:9ef0a014f1")
    api("com.github.vova7878.PanamaPort:LLVM:9ef0a014f1")

    // Implementation dependencies
    implementation("com.github.vova7878:AndroidMisc:v0.0.5")
    implementation("com.github.vova7878:SunCleanerStub:v0.0.3")
    implementation("com.github.vova7878:R8Annotations:v0.0.3")
}

afterEvaluate {
    publishing {
        publications {
            register<MavenPublication>("release") {
                from(components["release"])
                groupId = "com.github.Matrix-Username"
                artifactId = "Terminator"
                version = "1.0.0"
            }
        }
    }
}