import java.nio.charset.StandardCharsets

plugins {
    id("fabric-loom") version "1.14.10"
    java
}

evaluationDependsOn(":devils-shared")

val gameVersion = System.getenv("DEVILS_GAME_VERSION")
    ?.removePrefix("v")
    ?.trim()
    ?.takeIf { it.isNotEmpty() }
    ?: (findProperty("game_version_override") as String?)
        ?.removePrefix("v")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
    ?: (properties["game_version"] as String)

val minecraftVersion = properties["minecraft_version"] as String
val sharedMainOutput = project(":devils-shared")
    .extensions
    .getByType(org.gradle.api.tasks.SourceSetContainer::class.java)
    .named("main")
    .map { it.output }

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

base {
    archivesName = properties["game_archives_base_name"] as String
    version = gameVersion
    group = properties["maven_group"] as String
}

loom {
    runs {
        create("gamesRecoverySmoke") {
            client()
            ideConfigGenerated(false)
            configName = "Devils Game Recovery Smoke"
            runDir("run-devils-game-smoke")
            vmArg("-Ddevils.game.recovery.smoke=true")
            vmArg("-Ddevils.game.recovery.smoke.path=${rootProject.file("codex log/runtime-smoke.log").absolutePath}")
        }
    }
}

repositories {
    maven {
        name = "meteor-maven"
        url = uri("https://maven.meteordev.org/releases")
    }
    maven {
        name = "meteor-maven-snapshots"
        url = uri("https://maven.meteordev.org/snapshots")
    }
    maven {
        name = "Modrinth Maven"
        url = uri("https://api.modrinth.com/maven")
        content {
            includeGroup("maven.modrinth")
        }
    }
    mavenCentral()
}

dependencies {
    minecraft("com.mojang:minecraft:$minecraftVersion")
    mappings("net.fabricmc:yarn:${properties["yarn_mappings"] as String}:v2")
    modImplementation("net.fabricmc:fabric-loader:${properties["loader_version"] as String}")
    modImplementation("net.fabricmc.fabric-api:fabric-api:${properties["fabric_api_version"] as String}")
    modImplementation("meteordevelopment:meteor-client:$minecraftVersion-SNAPSHOT")
    implementation(project(":devils-shared"))

    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks {
    val validateGamesRecoverySmoke by registering {
        val runtimeSmokeLog = rootProject.file("codex log/runtime-smoke.log")

        doLast {
            check(runtimeSmokeLog.isFile) {
                "Devils Game recovery smoke log was not produced at ${runtimeSmokeLog.absolutePath}"
            }

            val lines = runtimeSmokeLog.readLines(StandardCharsets.UTF_8)
            check(lines.any { it.contains("RESULT PASS") }) {
                "Devils Game recovery smoke did not report PASS. See ${runtimeSmokeLog.absolutePath}"
            }
        }
    }

    named("runGamesRecoverySmoke") {
        doFirst {
            val smokeRunDir = layout.projectDirectory.dir("run-devils-game-smoke").asFile
            val staleEvidencePaths = listOf(
                smokeRunDir.resolve("config"),
                smokeRunDir.resolve("devils-game"),
                smokeRunDir.resolve("logs/latest.log")
            )

            staleEvidencePaths.forEach { path ->
                if (path.isDirectory) path.deleteRecursively()
                else path.delete()
            }
        }
        finalizedBy(validateGamesRecoverySmoke)
    }

    processResources {
        val propertyMap = mapOf(
            "version" to project.version,
            "mc_version" to minecraftVersion
        )

        inputs.properties(propertyMap)
        filteringCharset = "UTF-8"

        filesMatching("fabric.mod.json") {
            expand(propertyMap)
        }
    }

    jar {
        from(sharedMainOutput)

        from(rootProject.file("LICENSE")) {
            rename { "${it}_${project.base.archivesName.get()}" }
        }
    }

    test {
        useJUnitPlatform()
    }

    withType<JavaCompile> {
        options.encoding = "UTF-8"
        options.release = 21
        options.compilerArgs.add("-Xlint:deprecation")
        options.compilerArgs.add("-Xlint:unchecked")
    }
}
