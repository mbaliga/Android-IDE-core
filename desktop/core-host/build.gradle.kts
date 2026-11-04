// :core-host: the pure domain/ and contracts/ sources compiled and tested on a plain JVM.
// Sources are mapped by srcDir from :core-engine (the same pattern core-engine/build.gradle.kts
// uses for ../contracts/kotlin); nothing is copied. Pins match gradle/libs.versions.toml at the
// repo root (Kotlin 2.1.0, coroutines 1.10.1, junit 4.13.2); keep them in lockstep by hand.
plugins {
    kotlin("jvm") version "2.1.0"
}

kotlin {
    jvmToolchain(17)
}

val engine = rootDir.resolve("../core-engine/src")

sourceSets {
    main {
        kotlin.srcDir(engine.resolve("main/java/dev/aarso/domain"))
        kotlin.srcDir(rootDir.resolve("../contracts/kotlin"))
        // Back-edges from domain/ into data/ (dev.aarso.data.ReceiptStore, GitTransport).
        // They cannot compile until those edges are cut (plan LX-4); excluded, with their tests.
        // Compile-only android.icu stub (see the file); keeps Segmenter.kt unedited.
        kotlin.srcDir("src/hostStubs/kotlin")
        kotlin.exclude(
            "**/authority/AuditedAuthorityEngine.kt",
            "**/execution/CiActionsExecutionProvider.kt",
        )
    }
    test {
        kotlin.srcDir(engine.resolve("test/java/dev/aarso/domain"))
        kotlin.exclude(
            "**/authority/AuditedAuthorityEngineTest.kt",
            "**/execution/CiActionsExecutionProviderTest.kt",
        )
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")
    // Android supplies its own org.json; a plain JVM does not. Test-only at the root today, so
    // :core-engine:checkLicense has not scanned it. OPEN: org.json:json is under the "JSON
    // License", which is not in config/allowed-licenses.json; a runtime use needs a ruling.
    implementation("org.json:json:20231013")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")
}

tasks.test {
    useJUnit()
}
