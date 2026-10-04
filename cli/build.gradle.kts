plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

kotlin { jvmToolchain(21) }

application { mainClass.set("moe.rukamori.archivetune.cli.MainKt") }

dependencies {
    // Shared, already plain-JVM modules. :core carries the whole InnerTube /
    // YouTube Music client, so the CLI gets search, browse, playlists and stream
    // resolution without reimplementing any of it.
    implementation(project(":core"))
    implementation(project(":lastfm"))
    implementation(project(":spotifycore"))
    implementation(project(":canvas"))
    implementation(project(":shazamkit"))
    implementation(project(":lyrics:betterlyrics"))
    implementation(project(":lyrics:lrclib"))
    implementation(project(":lyrics:kugou"))
    implementation(project(":lyrics:simpmusic"))
    implementation(project(":lyrics:paxsenix"))
    implementation(project(":lyrics:unison"))
    implementation(project(":lyrics:youlyplus"))

    implementation(libs.clikt)
    implementation(libs.mordant)
    implementation(libs.vlcj)
    // Replaces android.icu for lyric romanization, which LyricsUtils relies on.
    implementation(libs.icu4j)

    // JLine is what gives the CLI a real terminal on Windows: raw mode,
    // alternate screen and readline. The `jdk11` classifier drops the FFM
    // terminal provider, whose classes are compiled for Java 22.
    implementation(libs.jline) { artifact { classifier = "jdk11" } }

    implementation(libs.coroutines.core)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    // CIO + websockets drive the DevTools (CDP) client used by the built-in
    // browser login (auth youtube / spotify auth).
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.websockets)
    implementation(libs.kotlinx.serialization.json)

    // Only the Win32 entry points for CommandLineToArgvW / GetCommandLineW; the
    // JVM hands main() arguments the ANSI code page has already destroyed.
    implementation(libs.jna)

    // vlcj and Ktor log through slf4j; without a provider they print a warning
    // banner on stderr for every command. See cli/Logging.kt for the level.
    runtimeOnly(libs.slf4j.simple)

    testImplementation(libs.junit)
}

tasks.withType<Test>().configureEach { useJUnit() }

/**
 * Self-contained runnable jar so the CLI can be shipped as a single file on
 * Windows without needing a JRE on the PATH beyond the one that runs it.
 */
val fatJar = tasks.register<Jar>("fatJar") {
    archiveClassifier.set("all")
    manifest { attributes("Main-Class" to application.mainClass.get()) }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from(
        provider {
            configurations.runtimeClasspath.get()
                .filter { it.name.endsWith("jar") }
                .map { zipTree(it) }
        },
    ) {
        // Signature files from signed upstream jars break the merged archive.
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/versions/9/module-info.class")
    }
}

tasks.named("assemble") { dependsOn(fatJar) }

/**
 * A folder that is ready to copy somewhere and run: the jar next to the
 * `archivetune.bat` launcher. This is what you want on Windows, where the JVM
 * needs its encoding flags set before it starts and a bare `java -jar` will
 * not have them.
 */
val installCli = tasks.register<Sync>("installCli") {
    description = "Collects cli-all.jar and the launcher into build/install-cli."
    group = "distribution"
    into(layout.buildDirectory.dir("install-cli"))
    from(fatJar) { rename { "cli-all.jar" } }
    from(layout.projectDirectory.dir("src/dist"))
}

tasks.named("assemble") { dependsOn(installCli) }
