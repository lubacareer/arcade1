import org.gradle.jvm.application.tasks.CreateStartScripts

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
    // LWJGL's Cocoa backend must initialize on the first JVM thread.
    if (providers.systemProperty("os.name").get().startsWith("Mac", ignoreCase = true)) {
        jvmArgs("-XstartOnFirstThread")
    }
}

// Detect the launch OS, rather than the build OS, for portable Unix distributions.
tasks.named<CreateStartScripts>("startScripts") {
    doLast {
        val script = unixScript.readText()
        val optionsLine = Regex("(?m)^DEFAULT_JVM_OPTS=.*$")
        check(optionsLine.findAll(script).count() == 1) { "Cannot locate generated JVM options" }
        val macOptions = """
            case "${'$'}(uname -s)" in
              Darwin*) DEFAULT_JVM_OPTS="${'$'}DEFAULT_JVM_OPTS -XstartOnFirstThread" ;;
            esac
        """.trimIndent()
        unixScript.writeText(optionsLine.replace(script) { it.value + "\n" + macOptions })
    }
}
