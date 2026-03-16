import java.net.URI

plugins {
    id("fabric-loom") version "1.14-SNAPSHOT"
    java
}

fun runGit(vararg args: String): String? {
    return try {
        val process = ProcessBuilder("git", *args)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText().trim()
        if (process.waitFor() == 0 && output.isNotEmpty()) output else null
    } catch (_: Exception) {
        null
    }
}

fun resolveVersionFromGitTags(): String? {
    val described = runGit(
        "describe",
        "--tags",
        "--match",
        "v[0-9]*.[0-9]*.[0-9]*",
        "--long",
        "--dirty"
    ) ?: return null

    // Examples:
    // v0.0.15-0-gabc1234
    // v0.0.15-3-gabc1234
    // v0.0.15-3-gabc1234-dirty
    val match = Regex("^v(\\d+\\.\\d+\\.\\d+)-(\\d+)-g([0-9a-f]+)(-dirty)?$")
        .matchEntire(described)
        ?: return described.removePrefix("v").takeIf { it.isNotBlank() }

    val base = match.groupValues[1]
    val commitsAhead = match.groupValues[2].toIntOrNull() ?: 0
    val sha = match.groupValues[3]
    val dirty = match.groupValues[4].isNotEmpty()

    if (commitsAhead == 0 && !dirty) return base

    val localMeta = buildString {
        append("g")
        append(sha)
        if (dirty) append(".dirty")
    }
    return "$base-dev.$commitsAhead+$localMeta"
}

fun parseSemverBase(version: String): Triple<Int, Int, Int>? {
    val match = Regex("^(\\d+)\\.(\\d+)\\.(\\d+).*").matchEntire(version) ?: return null
    return Triple(
        match.groupValues[1].toInt(),
        match.groupValues[2].toInt(),
        match.groupValues[3].toInt()
    )
}

fun compareSemverBase(left: String, right: String): Int {
    val l = parseSemverBase(left) ?: return 0
    val r = parseSemverBase(right) ?: return 0
    if (l.first != r.first) return l.first.compareTo(r.first)
    if (l.second != r.second) return l.second.compareTo(r.second)
    return l.third.compareTo(r.third)
}

val appVersionFromEnv = System.getenv("APP_VERSION")
    ?.removePrefix("v")
    ?.trim()
    ?.takeIf { it.isNotEmpty() }
val appVersionFromProperty = (findProperty("app_version") as String?)
    ?.removePrefix("v")
    ?.trim()
    ?.takeIf { it.isNotEmpty() }
val modVersionFallback = (properties["mod_version"] as String)
val appVersionFromGit = resolveVersionFromGitTags()
    ?.let { gitVersion ->
        if (compareSemverBase(gitVersion, modVersionFallback) < 0) modVersionFallback
        else gitVersion
    }
val resolvedAppVersion = appVersionFromEnv
    ?: appVersionFromProperty
    ?: appVersionFromGit
    ?: modVersionFallback

val minecraftVersion = properties["minecraft_version"] as String
val xaeroMinimapVersion = properties["xaero_minimap_version"] as String
val xaeroWorldMapVersion = properties["xaero_worldmap_version"] as String
val xaeroPlusVersion = properties["xaeroplus_version"] as String
val xaeroMinimapJar = "xaeros-minimap-$xaeroMinimapVersion.jar"
val xaeroWorldMapJar = "xaeros-world-map-$xaeroWorldMapVersion.jar"
val xaeroPlusJar = "xaeroplus-$xaeroPlusVersion.jar"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

base {
    archivesName = properties["archives_base_name"] as String
    version = resolvedAppVersion
    group = properties["maven_group"] as String
}

loom {
    accessWidenerPath = file("src/main/resources/devils-addon.accesswidener")
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
    maven {
        name = "Xaero Maven"
        url = uri("https://maven.2b2t.vc/xaero")
        content {
            includeGroup("xaero.lib")
        }
    }
    maven {
        name = "Xander Maven"
        url = uri("https://maven.isxander.dev/releases")
        content {
            includeGroupAndSubgroups("dev.isxander")
            includeGroupAndSubgroups("org.quiltmc")
        }
    }
    maven {
        name = "BlameJared"
        url = uri("https://maven.blamejared.com")
        content {
            includeGroupAndSubgroups("com.blamejared.searchables")
        }
    }
    maven {
        name = "TerraformersMC"
        url = uri("https://maven.terraformersmc.com/releases/")
        content {
            includeGroup("com.terraformersmc")
        }
    }
    maven {
        name = "WTHIT"
        url = uri("https://maven2.bai.lol")
        content {
            includeGroupAndSubgroups("mcp.mobius.waila")
            includeGroupAndSubgroups("lol.bai")
        }
    }
    maven {
        name = "MisterPeModder"
        url = uri("https://maven.misterpemodder.com/libs-release/")
        content {
            includeGroupAndSubgroups("com.misterpemodder")
        }
    }
}

dependencies {
    // Fabric
    minecraft("com.mojang:minecraft:$minecraftVersion")
    mappings("net.fabricmc:yarn:${properties["yarn_mappings"] as String}:v2")
    modImplementation("net.fabricmc:fabric-loader:${properties["loader_version"] as String}")
    modImplementation("net.fabricmc.fabric-api:fabric-api:${properties["fabric_api_version"] as String}")

    // Meteor
    modImplementation("meteordevelopment:meteor-client:$minecraftVersion-SNAPSHOT")

    // Local OGG playback (jar-in-jar)
    implementation("com.googlecode.soundlibs:vorbisspi:1.0.3.3")
    include("com.googlecode.soundlibs:vorbisspi:1.0.3.3")
    implementation("com.googlecode.soundlibs:tritonus-share:0.3.7.4")
    include("com.googlecode.soundlibs:tritonus-share:0.3.7.4")
    implementation("com.googlecode.soundlibs:jorbis:0.0.17.4")
    include("com.googlecode.soundlibs:jorbis:0.0.17.4")

    // Tests
    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks {
    val embeddedXaeroJars = listOf(
        Triple(
            xaeroMinimapJar,
            "https://api.modrinth.com/maven/maven/modrinth/xaeros-minimap/$xaeroMinimapVersion/$xaeroMinimapJar",
            xaeroMinimapJar
        ),
        Triple(
            xaeroWorldMapJar,
            "https://api.modrinth.com/maven/maven/modrinth/xaeros-world-map/$xaeroWorldMapVersion/$xaeroWorldMapJar",
            xaeroWorldMapJar
        ),
        Triple(
            xaeroPlusJar,
            "https://api.modrinth.com/maven/maven/modrinth/xaeroplus/$xaeroPlusVersion/$xaeroPlusJar",
            xaeroPlusJar
        )
    )

    val prepareEmbeddedXaeroJars by registering {
        val targetDir = layout.projectDirectory.dir("embedded-libs")
        outputs.files(embeddedXaeroJars.map { targetDir.file(it.first) })
        doLast {
            val dir = targetDir.asFile
            dir.mkdirs()
            embeddedXaeroJars.forEach { (name, url, _) ->
                val output = dir.resolve(name)
                if (output.isFile && output.length() > 0L) return@forEach

                logger.lifecycle("Downloading embedded Xaero jar: {}", name)
                URI.create(url).toURL().openStream().use { input ->
                    output.outputStream().use { out -> input.copyTo(out) }
                }
            }
        }
    }

    val cleanLibsJars by registering(Delete::class) {
        delete(fileTree(layout.buildDirectory.dir("libs")) { include("*.jar") })
    }

    val rebuildEmbeddedChestTrackerJar by registering(Exec::class) {
        inputs.files(
            fileTree("chesttracker-port-1.21.11/src"),
            file("chesttracker-port-1.21.11/build.gradle.kts"),
            file("chesttracker-port-1.21.11/settings.gradle.kts"),
            file("chesttracker-port-1.21.11/gradle.properties")
        )
        outputs.file(layout.projectDirectory.file("chesttracker-port-1.21.11/chesttracker-port-embedded.jar"))
        workingDir = project.projectDir
        commandLine("cmd", "/c", "gradlew.bat", "-p", "chesttracker-port-1.21.11", "remapJar", "--no-daemon")
    }

    val prepareEmbeddedChestTrackerJar by registering(Copy::class) {
        dependsOn(rebuildEmbeddedChestTrackerJar)
        from("chesttracker-port-1.21.11/build/libs/devils-addon-chesttracker-2.8.1-devils-1.21.11.jar")
        into("chesttracker-port-1.21.11")
        rename { "chesttracker-port-embedded.jar" }
    }

    processResources {
        val propertyMap = mapOf(
            "version" to project.version,
            "mc_version" to minecraftVersion,
            "xaero_minimap_jar" to xaeroMinimapJar,
            "xaero_worldmap_jar" to xaeroWorldMapJar,
            "xaeroplus_jar" to xaeroPlusJar,
        )

        inputs.properties(propertyMap)

        filteringCharset = "UTF-8"

        filesMatching("fabric.mod.json") {
            expand(propertyMap)
        }
    }

    jar {
        dependsOn(cleanLibsJars, prepareEmbeddedXaeroJars, prepareEmbeddedChestTrackerJar)
        inputs.property("archivesName", project.base.archivesName.get())

        from("LICENSE") {
            rename { "${it}_${inputs.properties["archivesName"]}" }
        }

        embeddedXaeroJars.forEach { (name, _, outputName) ->
            from("embedded-libs/$name") {
                into("META-INF/jars")
                rename { outputName }
            }
        }

        from("chesttracker-port-1.21.11/chesttracker-port-embedded.jar") {
            into("META-INF/jars")
            rename { "chesttracker-port-embedded.jar" }
        }
    }

    java {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
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
