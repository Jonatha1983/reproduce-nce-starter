# reproduce-nce-starter

Minimal reproducer for a `NoClassDefFoundError: jetbrains/buildServer/messages/serviceMessages/ServiceMessage`
crash in **`TestFrameworkType.Starter`** tests, using **IntelliJ Platform Gradle Plugin 2.18.1** and
**IntelliJ Platform 2026.2**.

Created from the stock [IntelliJ Platform Plugin Template][template]. The only changes on top of the
template are listed in [What was changed](#what-was-changed-vs-the-stock-template).

## Reproduce

```bash
./gradlew starterTest
```

The single Starter test ([`StarterServiceMessageRepro`](src/starterTest/kotlin/com/github/jonatha1983/reproducencestarter/StarterServiceMessageRepro.kt))
does nothing IDE-specific — it launches an IDE via the Starter framework and closes it again.
The crash fires in the **framework's own cleanup code** after the IDE process exits:

```
Exception starting IDE. Even if it was started, it will be killed now.
java.lang.NoClassDefFoundError: jetbrains/buildServer/messages/serviceMessages/ServiceMessage
    at com.intellij.platform.testFramework.teamCity.TeamCityReporter.serviceMessage(TeamCityReporter.kt:70)
    at com.intellij.platform.testFramework.teamCity.TeamCityReporter.reportTestMetadata(TeamCityReporter.kt:200)
    at com.intellij.ide.starter.runner.LocalIDEProcess.run(LocalIDEProcess.kt:163)
    ...
Caused by: java.lang.ClassNotFoundException: jetbrains.buildServer.messages.serviceMessages.ServiceMessage
```

## Make it pass

Uncomment the single workaround line in [`build.gradle.kts`](build.gradle.kts):

```kotlin
"starterTestRuntimeOnly"("org.jetbrains.teamcity:serviceMessages:2024.07")
```

and run `./gradlew starterTest` again — the same test passes.

## Root cause

1. `org.jetbrains.teamcity:serviceMessages` is a **hardcoded entry** in the Gradle plugin's
   [`explicitExclusions`](https://github.com/JetBrains/intellij-platform-gradle-plugin/blob/2.18.1/src/main/kotlin/org/jetbrains/intellij/platform/gradle/providers/ModuleDescriptorsValueSource.kt#L123-L128)
   (added in `3e3f12c20b1e`, 2024-03-11). These exclusions — plus every library listed in the IDE
   distribution's `module-descriptors.jar` — are attached as Gradle `exclude` rules to **every**
   dependency the plugin adds for a test framework.
2. That is correct for the in-IDE frameworks (`TestFrameworkType.Platform` tests run *inside* the IDE,
   which bundles the class) — but **wrong for Starter**, which runs the test in a **plain external JVM**
   where the IDE distribution is the product under test, not the host. The Starter squashed jar shades
   platform utilities but not `jetbrains/buildServer/messages/**`.
3. `test-framework-core` declares `org.jetbrains.teamcity:serviceMessages:2024.07`, but the exclude rule
   strips it from every resolution path. Since IJPGP **2.18.0** auto-imports all `ide-starter-product-*`
   artifacts (platform builds ≥ 262), no unfiltered transitive path remains — before 2.18, the manually
   added product artifact carried no excludes and accidentally provided the class. Proof:

   ```bash
   ./gradlew dependencyInsight --configuration starterTestRuntimeClasspath --dependency serviceMessages
   # -> "No dependencies matching given input were found in configuration ':starterTestRuntimeClasspath'"
   ```
4. Since intellij-community `30aa50768e0d` (AT-4371, 2026-04-09) the Starter side calls
   `TeamCityReporter.reportTestMetadata` **unconditionally** in `LocalIDEProcess.run`'s cleanup —
   previously the call was gated on running under TeamCity CI. The surrounding `catch` handles only
   `Exception`, so the linkage `Error` escapes and fails the run.

There is no user-side opt-out: no Gradle property affects the exclusion list, the `testFramework()` DSL
takes only `(type, version, configurationName)`, and Gradle's API can add but not remove exclude rules.

## The same exclusions break Starter in three more ways

`serviceMessages` is the headline because it is what hits established projects (whose classpaths already
carry the other libraries). Building this reproducer **from the pristine template** surfaced three more
victims of the same exclude rules — each had to be shimmed in `build.gradle.kts` before the
serviceMessages crash could even be reached:

1. **kotlinx-coroutines** (in `explicitExclusions` via `+ coroutines`): without a manual
   `kotlinx-coroutines-core-jvm` dependency, `Starter.newContext(...)` dies instantly.
   (Extra twist: the framework declares `1.10.2-intellij-1`, which does not resolve from any of the
   repositories `defaultRepositories()` configures — so even hand-adding the *declared* version fails;
   this repo shims the vanilla `1.10.2` instead.)

   ```
   java.lang.NoClassDefFoundError: Could not initialize class com.intellij.tools.ide.starter.bus.EventsBus
       at com.intellij.ide.starter.runner.TestContainer.<clinit>(TestContainer.kt:31)
       ...
   Caused by: java.lang.NoClassDefFoundError: kotlinx/coroutines/JobKt
       at com.intellij.tools.ide.starter.bus.local.LocalEventsFlow.<init>(LocalEventsFlow.kt:35)
   ```

2. **kotlin-stdlib** (in `explicitExclusions` via `+ kotlinStdlib`): with the template's
   `kotlin.stdlib.default.dependency=false`, the Starter test source set cannot even compile.
3. **junit:junit** (explicit entry in `explicitExclusions`): the Starter dependencies drag in the
   JUnit *vintage* engine, but its `junit:junit` core is stripped, so every test run fails with
   `TestEngine with ID 'junit-vintage' failed to discover tests` (worked around here by
   `excludeEngines("junit-vintage")`).

All four are the same root cause: bundled-library exclude rules that are only valid for test frameworks
running *inside* the IDE, applied to a framework that runs *outside* it.

## What was changed vs. the stock template

- `settings.gradle.kts`: `org.jetbrains.intellij.platform.settings` **2.16.0 → 2.18.1**, Kotlin
  **2.1.20 → 2.4.0** (the 262 Starter jars need 2.x-era metadata)
- `build.gradle.kts`: platform **`intellijIdea("2025.2.6.2")` → `intellijIdea("2026.2")`** (the Starter
  auto-import gate is build ≥ 262), `jvmToolchain(25)` (the 262 Starter jars are class-file version 69),
  a `starterTest` source set + task with `testFramework(TestFrameworkType.Starter)`, JUnit 5, the
  prerequisite shims from the section above, and the commented-out serviceMessages workaround line
- `src/starterTest/…/StarterServiceMessageRepro.kt`: the one Starter test

## Environment

- IntelliJ Platform Gradle Plugin **2.18.1** (also reproduces on 2.18.0)
- IntelliJ Platform `intellijIdea("2026.2")`; Starter launches IntelliJ IDEA `2026.2`
- Gradle 9.5, Kotlin 2.4.0, JVM toolchain 25

---
Based on the [IntelliJ Platform Plugin Template][template].

[template]: https://github.com/JetBrains/intellij-platform-plugin-template
