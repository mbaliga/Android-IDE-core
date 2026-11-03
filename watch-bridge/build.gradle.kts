// :watch-bridge — the WatchBrew wire contract (docs/design/watchbrew.md §2). A plain Kotlin/JVM
// library, deliberately NOT an Android library: no Android plugin, no androidx, no Play Services,
// no Compose, no coroutines. Both sides of the pair (phone-side relay in :core-engine, the Wear OS
// app in :watchbrew) consume it; nothing else does. See WatchBridge.kt for the full module notes.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
    // A wire contract is a public surface by definition — make every visibility and every public
    // return type an explicit, reviewed decision rather than an inferred one.
    explicitApi()
}

dependencies {
    // org.json is the house JSON library (core-engine's domain/contracts EnvelopeCodec idiom). On
    // Android it is a PLATFORM class (phone and Wear OS both ship it), so it is compileOnly here:
    // bundling a second copy into an APK is exactly what we don't want, and it keeps this module's
    // published dependency set empty. Consequence, stated not hidden: a consumer's own JVM unit
    // tests need a real org.json on THEIR test classpath (AGP's android.jar stub throws "not
    // mocked") — :core-engine already does this the same way (testImplementation org.json:json).
    compileOnly("org.json:json:20231013")

    testImplementation(libs.junit)
    testImplementation("org.json:json:20231013")
}
