package dev.aarso.domain.ide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectScaffoldTest {

    private val spec = ProjectScaffold.AppSpec(
        appName = "Tasklet",
        packageId = "com.example.tasklet",
        idea = "A tiny to-do list.",
    )

    @Test fun `package id validation`() {
        assertTrue(ProjectScaffold.isValidPackageId("com.example.app"))
        assertFalse(ProjectScaffold.isValidPackageId("noDots"))
        assertFalse(ProjectScaffold.isValidPackageId("com.1bad.seg")) // segment starts with a digit
        assertFalse(ProjectScaffold.isValidPackageId("com..empty"))
    }

    @Test fun `validate flags the obvious problems`() {
        assertTrue(ProjectScaffold.validate(spec).isEmpty())
        val bad = spec.copy(appName = "", packageId = "x", compileSdk = 10, minSdk = 20)
        val problems = ProjectScaffold.validate(bad)
        assertTrue(problems.any { it.contains("name") })
        assertTrue(problems.any { it.contains("Package id") })
    }

    @Test fun `generate refuses an invalid spec`() {
        assertThrows(IllegalArgumentException::class.java) {
            ProjectScaffold.generate(spec.copy(packageId = "nope"))
        }
    }

    @Test fun `generate lays the package path and wires the id everywhere`() {
        val files = ProjectScaffold.generate(spec).associateBy { it.path }

        // MainActivity lands under the package path.
        val main = files["app/src/main/java/com/example/tasklet/MainActivity.kt"]
        requireNotNull(main)
        assertTrue(main.content.startsWith("package com.example.tasklet"))

        // The application id is threaded through the app build script.
        val appGradle = files.getValue("app/build.gradle.kts").content
        assertTrue(appGradle.contains("""applicationId = "com.example.tasklet""""))
        assertTrue(appGradle.contains("namespace = \"com.example.tasklet\""))
        assertTrue(appGradle.contains("minSdk = 26"))

        // The idea text shows on the generated home + the app name in strings.
        assertTrue(main.content.contains("A tiny to-do list."))
        assertTrue(files.getValue("app/src/main/res/values/strings.xml").content.contains(">Tasklet<"))
    }

    @Test fun `generate includes a CI workflow that emits an apk artifact`() {
        val files = ProjectScaffold.generate(spec).associateBy { it.path }
        val ci = files["/github/workflows/build.yml"] ?: files[".github/workflows/build.yml"]
        requireNotNull(ci)
        assertTrue(ci.content.contains("assembleDebug"))
        assertTrue(ci.content.contains("upload-artifact"))
    }

    @Test fun `every generated path is unique and non-empty`() {
        val files = ProjectScaffold.generate(spec)
        assertEquals(files.size, files.map { it.path }.toSet().size)
        assertTrue(files.all { it.path.isNotBlank() && it.content.isNotBlank() })
    }

    @Test fun `blank idea falls back to a default home line`() {
        val files = ProjectScaffold.generate(spec.copy(idea = "")).associateBy { it.path }
        assertTrue(files.getValue("app/src/main/java/com/example/tasklet/MainActivity.kt").content.contains("conceived on Aarso"))
    }
}
