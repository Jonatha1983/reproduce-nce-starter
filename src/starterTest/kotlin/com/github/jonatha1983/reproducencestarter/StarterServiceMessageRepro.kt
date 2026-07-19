package com.github.jonatha1983.reproducencestarter

import com.intellij.ide.starter.driver.engine.runIdeWithDriver
import com.intellij.ide.starter.models.IdeInfo
import com.intellij.ide.starter.models.TestCase
import com.intellij.ide.starter.project.NoProject
import com.intellij.ide.starter.runner.Starter
import com.intellij.tools.ide.starter.product.idea.ultimate.IdeaUltimate
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.minutes

/**
 * Reproduces `NoClassDefFoundError: jetbrains/buildServer/messages/serviceMessages/ServiceMessage`.
 *
 * The test body is intentionally empty of IDE interactions: it launches an IDE via the
 * Starter framework and closes it again. The crash fires inside the framework's own
 * cleanup path (`LocalIDEProcess.run` -> `TeamCityReporter.reportTestMetadata`) after the
 * IDE process exits, because the IntelliJ Platform Gradle Plugin attaches an exclude rule
 * for `org.jetbrains.teamcity:serviceMessages` to every dependency it adds for
 * `TestFrameworkType.Starter` — but Starter tests run in a plain JVM outside the IDE,
 * where nothing else provides that class.
 *
 * See the one-line workaround commented out in build.gradle.kts.
 */
class StarterServiceMessageRepro {

  @Test
  fun `launching and closing an IDE via Starter crashes in TeamCityReporter`() {
    val testCase = TestCase(ideInfo = IdeInfo.IdeaUltimate, projectInfo = NoProject).useRelease("2026.2")

    val run = Starter.newContext(testName = "service-message-repro", testCase = testCase)
      .applyVMOptionsPatch {
        // Keep the freshly-unpacked IDE from blocking on first-start dialogs.
        addSystemProperty("jb.consents.confirmation.enabled", false)
        addSystemProperty("jb.privacy.policy.text", "<!--999.999-->")
        addSystemProperty("ide.show.tips.on.startup.default.value", false)
      }
      .runIdeWithDriver(runTimeout = 10.minutes)

    run.closeIdeAndWait()
  }
}
