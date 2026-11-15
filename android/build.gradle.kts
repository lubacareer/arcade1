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
// AGP host tools carry independent transitive dependencies. Keep their security fixes
// scoped here so these overrides never change the game's Android runtime classpath.
// Guava: https://github.com/google/guava/releases/tag/v33.7.2
// Netty: https://github.com/netty/netty/releases/tag/netty-4.1.140.Final
// Bouncy Castle: https://www.bouncycastle.org/download/bouncy-castle-java/
configurations.configureEach {
    if (name == "androidLintTool" || name.startsWith("unified-test-platform-")) {
        resolutionStrategy.eachDependency {
            val patchedVersion = when {
                requested.group == "com.google.guava" && requested.name == "guava" -> "33.7.2-jre"
                // The optional tcnative family has its own version scheme.
                requested.group == "io.netty" && !requested.name.startsWith("netty-tcnative") -> "4.1.140.Final"
                requested.group == "org.bouncycastle" &&
                    requested.name in setOf("bcprov-jdk18on", "bcpkix-jdk18on", "bcutil-jdk18on") -> "1.87"
                else -> null
            }
            if (patchedVersion != null) {
                useVersion(patchedVersion)
                because("Patch vulnerable AGP lint and device-test tooling dependencies")
            }
        }
    }
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
