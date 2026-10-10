import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    kotlin("jvm") version "2.2.21"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "com.fabianreyes"
version = "0.1.1"

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

dependencies {
    intellijPlatform {
        intellijIdeaCommunity("2025.1.7")
        // Compile-time only: the GitHub plugin is an optional dependency in plugin.xml.
        bundledPlugin("org.jetbrains.plugins.github")
        testFramework(TestFrameworkType.Platform)
    }
    testImplementation("junit:junit:4.13.2")
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmDefault.set(org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode.NO_COMPATIBILITY)
        // 2025.1 bundles the Kotlin 2.1 stdlib: no newer language or library features.
        apiVersion.set(KotlinVersion.KOTLIN_2_1)
        languageVersion.set(KotlinVersion.KOTLIN_2_1)
    }
}

tasks.test {
    testLogging.exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "251"
            untilBuild = provider { null }
        }
    }
    pluginVerification {
        ides {
            current()
            providers.gradleProperty("verificationIdePath").orNull?.let { local(file(it)) }
        }
    }
}

intellijPlatformTesting {
    runIde {
        register("runAndroidStudio") {
            localPath = file("/Applications/Android Studio.app")
        }
    }
}

// The bundled review profiles live at the repository root, shared with the VS Code extension.
tasks.processResources {
    from("../LICENSE") { into("META-INF") }
    val profiles = layout.projectDirectory.dir("../profiles")
    from(profiles) {
        include("*.md")
        into("profiles")
    }
    doLast {
        // Jars cannot list directories: bundledProfiles() reads this index.
        val names = profiles.asFile.listFiles { f -> f.name.endsWith(".md") }!!.map { it.name }.sorted()
        destinationDir.resolve("profiles/index.txt").writeText(names.joinToString("\n") + "\n")
    }
}
