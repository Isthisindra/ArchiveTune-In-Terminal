@file:Suppress("UnstableApiUsage")

pluginManagement {
    repositories {
        google {
            content {
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
                includeGroupAndSubgroups("androidx")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)

    repositories {
        google()
        mavenCentral {
            mavenContent {
                releasesOnly()
            }
        }
        exclusiveContent {
            forRepository {
                maven {
                    name = "JitPack"
                    setUrl("https://jitpack.io")
                }
            }
            filter {
                includeGroup("com.github.therealbush")
                includeGroup("com.github.TeamNewPipe")
            }
        }
    }
}

// F-Droid doesn't support foojay-resolver plugin, so it stays opt-in. The CLI
// enables it via -Pcli so a JDK 21 toolchain can be auto-provisioned on machines
// that only have an older JDK on PATH.
// if (providers.gradleProperty("cli").isPresent) {
//     id("org.gradle.toolchains.foojay-resolver-convention") version("1.0.0") in settings
// }

rootProject.name = "ArchiveTune"

include(":cli")
include(":core")
include(":lyrics:kugou")
include(":lyrics:lrclib")
include(":lyrics:simpmusic")
include(":lyrics:paxsenix")
include(":lyrics:betterlyrics")
include(":lyrics:unison")
include(":lyrics:youlyplus")
include(":lastfm")
include(":canvas")
include(":shazamkit")
include(":spotifycore")

// The Android-only modules are skipped when no Android SDK is available, so the
// CLI (and every plain-JVM module) still configures on a machine that only has a
// JDK installed. Override with -PwithAndroid=<path-to-sdk> or -PwithAndroid=true
// when an SDK is present but not auto-detected.
val withAndroid: String? = providers.gradleProperty("withAndroid").orNull

val androidSdkAvailable: Boolean =
    when {
        withAndroid != null -> withAndroid.equals("true", ignoreCase = true) || File(withAndroid).isDirectory
        else ->
            System.getenv("ANDROID_HOME") != null ||
                System.getenv("ANDROID_SDK_ROOT") != null ||
                File("local.properties")
                    .takeIf { it.isFile }
                    ?.let { props ->
                        java.util.Properties().apply { props.inputStream().use(::load) }
                            .getProperty("sdk.dir")
                            ?.let { File(it).isDirectory } == true
                    } == true
    }

if (androidSdkAvailable) {
    include(":app")
    include(":morideobfuscator")
}

gradle.rootProject {
    if (!androidSdkAvailable) {
        logger.lifecycle(
            "[ArchiveTune] No Android SDK detected - skipping :app and :morideobfuscator. " +
                "The :cli module and all plain-JVM modules still build.",
        )
    }
}

// Use a local copy of NewPipe Extractor by uncommenting the lines below.
// We assume, that ArchiveTune and NewPipe Extractor have the same parent directory.
// If this is not the case, please change the path in includeBuild().
//
// For this to work you also need to change the implementation in core/build.gradle.kts
// to one which does not specify a version.
// From:
//      implementation(libs.newpipe.extractor)
// To:
//      implementation("com.github.TeamNewPipe:NewPipeExtractor")
// includeBuild("../NewPipeExtractor") {
//    dependencySubstitution {
//        substitute(module("com.github.TeamNewPipe:NewPipeExtractor")).using(project(":extractor"))
//    }
// }
