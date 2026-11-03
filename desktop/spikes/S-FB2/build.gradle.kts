// S-FB2: the FTS5 SearchDatabase (SQLDelight + eygraber androidx driver + sqlite-bundled) and the
// Room database open together in ONE JVM process, at the CURRENT core pins.
//
// Pins copied from /gradle/libs.versions.toml (do not drift): kotlin 2.1.0, ksp 2.1.0-1.0.29,
// room 2.7.1, sqldelight 2.1.0, sqldelightAndroidxDriver 0.0.1, sqliteBundled 2.7.0, coroutines 1.10.1.

plugins {
    kotlin("multiplatform") version "2.1.0"
    id("com.google.devtools.ksp") version "2.1.0-1.0.29"
    id("app.cash.sqldelight") version "2.1.0"
}

val roomVersion = "2.7.1"
val sqldelightVersion = "2.1.0"
val sqldelightAndroidxDriverVersion = "0.0.1"
val sqliteBundledVersion = "2.7.0"
val coroutinesVersion = "1.10.1"

// ---- The real sources, mapped byte-for-byte from :core-engine (no Gradle dependency on it) ----
//   * Search.sq              -> SQLDelight generates the real SearchDatabase from it
//   * SearchDriverFactory.kt -> the real driver idiom incl. the raw-SQL trigger workaround; its only
//                               Android dependency is `android.content.Context` (create(context)),
//                               satisfied here by a 5-line stub (src/jvmMain/.../android/content)
//   * MessageNodeEntity/Dao  -> the real Room entity + DAO
val coreMain = layout.projectDirectory.dir("../../../core-engine/src/main")
val mapSq = tasks.register<Sync>("mapCoreSearchSq") {
    from(coreMain.dir("sqldelight")) { include("dev/fonebrew/data/search/Search.sq") }
    into(layout.buildDirectory.dir("mapped-core/sqldelight"))
}
val mapRoom = tasks.register<Sync>("mapCoreRoomSources") {
    from(coreMain.dir("java/dev/fonebrew")) {
        include("data/entity/MessageNodeEntity.kt", "data/dao/MessageNodeDao.kt")
    }
    into(layout.buildDirectory.dir("mapped-core/room"))
}
val mapFactory = tasks.register<Sync>("mapCoreSearchDriverFactory") {
    from(coreMain.dir("java/dev/fonebrew")) { include("data/search/SearchDriverFactory.kt") }
    into(layout.buildDirectory.dir("mapped-core/factory"))
}

kotlin {
    jvmToolchain(17)
    jvm()
    compilerOptions { freeCompilerArgs.add("-Xexpect-actual-classes") }

    sourceSets {
        commonMain {
            kotlin.srcDir(mapRoom)
            dependencies {
                implementation("androidx.room:room-runtime:$roomVersion")
                implementation("androidx.sqlite:sqlite-bundled:$sqliteBundledVersion")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")
                implementation("app.cash.sqldelight:runtime:$sqldelightVersion")
                implementation("com.eygraber:sqldelight-androidx-driver:$sqldelightAndroidxDriverVersion")
            }
        }
        jvmMain { kotlin.srcDir(mapFactory) }
        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation(kotlin("test-junit"))
        }
    }
}

dependencies {
    add("kspJvm", "androidx.room:room-compiler:$roomVersion")
}

sqldelight {
    databases {
        create("SearchDatabase") {
            packageName.set("dev.fonebrew.data.search")
            srcDirs.setFrom(layout.buildDirectory.dir("mapped-core/sqldelight"))
            // FINDING F3: in :core-engine the SQLDelight *Android* plugin infers the SQL dialect from
            // minSdk (31 -> sqlite-3-30). A KMP/JVM project has no minSdk and silently falls back to
            // sqlite-3-18, which rejects Search.sq's `ON CONFLICT ... DO UPDATE` (UPSERT needs 3.24).
            // The dialect must therefore be pinned explicitly here.
            dialect("app.cash.sqldelight:sqlite-3-30-dialect:$sqldelightVersion")
        }
    }
}
// SQLDelight's `srcDirs` drops the producing task's build dependency (the generate task came up
// NO-SOURCE with the Sync wired through srcDirs), so state the ordering explicitly.
tasks.matching { it.name.startsWith("generate") && it.name.endsWith("SearchDatabaseInterface") }
    .configureEach { dependsOn(mapSq) }

tasks.withType<Test>().configureEach {
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = false
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
