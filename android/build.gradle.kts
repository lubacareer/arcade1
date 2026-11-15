plugins { alias(libs.plugins.android.application) }
android {
    namespace = "io.github.lubacareer.spacepong"
    compileSdk = 37
    buildToolsVersion = "36.0.0"
    defaultConfig {
        applicationId = "io.github.lubacareer.spacepong"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += setOf("arm64-v8a", "x86_64") }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    sourceSets {
        getByName("main") {
            assets.srcDir("../assets")
            jniLibs.srcDir(layout.buildDirectory.dir("generated/jniLibs").get().asFile)
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        create("smoke") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
    }
    packaging { jniLibs { useLegacyPackaging = false } }
}
val natives by configurations.creating
dependencies {
    implementation(project(":game"))
    implementation(libs.gdx.android)
    implementation("androidx.annotation:annotation:1.9.1")
    add("natives", "com.badlogicgames.gdx:gdx-platform:${libs.versions.gdx.get()}:natives-arm64-v8a")
    add("natives", "com.badlogicgames.gdx:gdx-platform:${libs.versions.gdx.get()}:natives-x86_64")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
}
val extractNatives by tasks.registering {
    inputs.files(natives)
    outputs.dir(layout.buildDirectory.dir("generated/jniLibs"))
    doLast {
        natives.files.forEach { jar ->
            val abi = if (jar.name.contains("arm64-v8a")) "arm64-v8a" else "x86_64"
            copy {
                from(zipTree(jar))
                include("*.so")
                into(layout.buildDirectory.dir("generated/jniLibs/$abi"))
            }
        }
    }
}
tasks.named("preBuild") { dependsOn(extractNatives) }
