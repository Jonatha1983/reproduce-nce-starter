import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
    id("org.jetbrains.changelog")
}

kotlin {
    // The 262+ Starter test-framework jars are compiled for Java 25 (class file 69).
    jvmToolchain(25)
}

// Starter (out-of-process) tests get their own source set so they never share a
// classpath with the in-IDE Platform test framework used by src/test.
sourceSets {
    create("starterTest")
}

dependencies {
    testImplementation("junit:junit:4.13.2")

    // PREREQUISITE SHIMS — victims of the same IJPGP exclude rules, hit before the
    // headline serviceMessages crash even has a chance to fire in a pristine project:
    // kotlin-stdlib and kotlinx-coroutines are BOTH stripped from every Starter dependency
    // (explicitExclusions ends with `+ kotlinStdlib + coroutines`), and the template opts
    // out of the automatic stdlib (kotlin.stdlib.default.dependency=false). Without these,
    // `Starter.newContext` dies instantly with
    // `NoClassDefFoundError: kotlinx/coroutines/JobKt` in EventsBus's static init.
    // Versions mirror what the Starter framework itself declares (and IJPGP strips).
    "starterTestImplementation"(kotlin("stdlib"))
    // (The framework declares 1.10.2-intellij-1, which is not resolvable from public
    // repositories — the vanilla build works fine for the test JVM.)
    "starterTestRuntimeOnly"("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.10.2")

    "starterTestImplementation"(platform("org.junit:junit-bom:6.1.3"))
    "starterTestImplementation"("org.junit.jupiter:junit-jupiter")
    "starterTestRuntimeOnly"("org.junit.platform:junit-platform-launcher")

    // WORKAROUND for the NoClassDefFoundError this repo reproduces — uncommenting this
    // single line makes `./gradlew starterTest` pass:
    // "starterTestRuntimeOnly"("org.jetbrains.teamcity:serviceMessages:2024.07")

    // IntelliJ Platform Gradle Plugin Dependencies Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
    intellijPlatform {
        intellijIdea("2026.2")
        testFramework(TestFrameworkType.Platform)
        // Since IJPGP 2.18.0 this auto-imports all ide-starter-product-* artifacts
        // (platform build >= 262) — no manual product dependency needed or wanted.
        testFramework(TestFrameworkType.Starter, configurationName = "starterTestImplementation")
    }
}

tasks.register<Test>("starterTest") {
    description = "Runs the IDE Starter test that reproduces the ServiceMessage NoClassDefFoundError"
    group = "verification"
    testClassesDirs = sourceSets["starterTest"].output.classesDirs
    classpath = sourceSets["starterTest"].runtimeClasspath
    useJUnitPlatform {
        // The Starter dependencies drag in the vintage engine, but `junit:junit` is
        // ALSO stripped by the same IJPGP exclude rules, so vintage cannot start.
        excludeEngines("junit-vintage")
    }
    // The Starter framework downloads and launches a full IDE — never up-to-date.
    outputs.upToDateWhen { false }
}
