plugins {
    application
    alias(libs.plugins.kotlin.jvm)
}
kotlin {
    jvmToolchain(21)
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
dependencies {
    implementation(project(":game"))
    implementation(libs.gdx.lwjgl3)
    runtimeOnly("com.badlogicgames.gdx:gdx-platform:${libs.versions.gdx.get()}:natives-desktop")
}
application { mainClass.set("io.github.lubacareer.spacepong.desktop.DesktopLauncherKt") }
tasks.named<JavaExec>("run") {
    workingDir(rootProject.file("assets"))
    isIgnoreExitValue = false
}
