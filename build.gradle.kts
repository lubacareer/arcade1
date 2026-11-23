plugins {
    base
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.android.application) apply false
}
allprojects {
    group = "io.github.lubacareer.spacepong"
    version = "0.2.0"
    dependencyLocking { lockAllConfigurations() }
}
tasks.named("check") { dependsOn(":simulation:check", ":game:check", ":desktop:check", ":android:lintDebug") }

val ktlint by configurations.creating {
    attributes {
        attribute(org.gradle.api.attributes.Usage.USAGE_ATTRIBUTE, objects.named(org.gradle.api.attributes.Usage.JAVA_RUNTIME))
        attribute(org.gradle.api.attributes.Bundling.BUNDLING_ATTRIBUTE, objects.named(org.gradle.api.attributes.Bundling.SHADOWED))
    }
}

dependencies { ktlint("com.pinterest.ktlint:ktlint-cli:1.7.1") }

tasks.register<JavaExec>("ktlintCheck") {
    group = "verification"
    description = "Checks Kotlin source formatting without rewriting files."
    classpath = ktlint
    mainClass.set("com.pinterest.ktlint.Main")
    args("**/src/**/*.kt", "*.gradle.kts", "*/build.gradle.kts", "!**/build/**")
}
tasks.register<JavaExec>("ktlintFormat") {
    group = "formatting"
    classpath = ktlint
    mainClass.set("com.pinterest.ktlint.Main")
    args("--format", "**/src/**/*.kt", "*.gradle.kts", "*/build.gradle.kts", "!**/build/**")
}
tasks.named("check") { dependsOn("ktlintCheck") }
