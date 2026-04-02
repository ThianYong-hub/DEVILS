import java.io.File
import java.net.URI
import java.util.zip.ZipFile
import org.gradle.api.tasks.SourceSetContainer

plugins {
    id("fabric-loom") version "1.14.10"
    java
}

evaluationDependsOn(":devils-shared")

val xaeroPatchSourceSet = sourceSets.create("xaeroPatch") {
    java.srcDir(rootProject.file("xaero-patch-src"))
    compileClasspath += sourceSets["main"].compileClasspath + sourceSets["main"].output
    runtimeClasspath += output + compileClasspath
}

configurations[xaeroPatchSourceSet.compileOnlyConfigurationName].extendsFrom(configurations["compileOnly"])
configurations[xaeroPatchSourceSet.implementationConfigurationName].extendsFrom(configurations["compileOnly"])

val appVersionFromEnv = System.getenv("DEVILS_ADDON_VERSION")
    ?.removePrefix("v")
    ?.trim()
    ?.takeIf { it.isNotEmpty() }
val appVersionFromProperty = (findProperty("addon_version_override") as String?)
    ?.removePrefix("v")
    ?.trim()
    ?.takeIf { it.isNotEmpty() }
val modVersionFallback = (properties["addon_version"] as String)
val resolvedAppVersion = appVersionFromEnv
    ?: appVersionFromProperty
    ?: modVersionFallback

val minecraftVersion = properties["minecraft_version"] as String
val xaeroMinimapVersion = properties["xaero_minimap_version"] as String
val xaeroWorldMapVersion = properties["xaero_worldmap_version"] as String
val xaeroPlusVersion = properties["xaeroplus_version"] as String
val xaeroMinimapJar = "xaeros-minimap-$xaeroMinimapVersion.jar"
val xaeroWorldMapJar = "xaeros-world-map-$xaeroWorldMapVersion.jar"
val xaeroPlusJar = "xaeroplus-$xaeroPlusVersion.jar"
val xaeroAerolibJar = rootProject.file("tools/xaerolib-fabric-1.21.11-1.1.0.jar")
val sharedMainOutput = project(":devils-shared")
    .extensions
    .getByType(SourceSetContainer::class.java)
    .named("main")
    .map { it.output }

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

base {
    archivesName = properties["addon_archives_base_name"] as String
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
    implementation(project(":devils-shared"))

    // Xaero compile stubs for typed mixins against embedded jars.
    modCompileOnly("maven.modrinth:xaeros-minimap:$xaeroMinimapVersion")
    modCompileOnly("maven.modrinth:xaeros-world-map:$xaeroWorldMapVersion")
    modCompileOnly(files(xaeroAerolibJar))

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

    val xaeroPatchClassesJar by registering(Jar::class) {
        dependsOn(named("compileXaeroPatchJava"))
        from(xaeroPatchSourceSet.output)
        archiveBaseName.set("xaero-patch-classes")
        archiveVersion.set("1")
        archiveClassifier.set("named")
        destinationDirectory.set(layout.buildDirectory.dir("tmp/xaero-remap"))
    }

    val remapXaeroPatchClassesJar by registering(net.fabricmc.loom.task.RemapJarTask::class) {
        dependsOn(xaeroPatchClassesJar)
        inputFile.set(xaeroPatchClassesJar.flatMap { it.archiveFile })
        archiveBaseName.set("xaero-patch-classes")
        archiveVersion.set("1")
        archiveClassifier.set("intermediary")
        destinationDirectory.set(layout.buildDirectory.dir("tmp/xaero-remap"))
        addNestedDependencies.set(false)
    }

    val patchEmbeddedXaeroJars by registering {
        dependsOn(prepareEmbeddedXaeroJars, remapXaeroPatchClassesJar)

        val minimapJarName = xaeroMinimapJar
        val worldMapJarName = xaeroWorldMapJar
        val remappedPatchJarFile = remapXaeroPatchClassesJar.flatMap { it.archiveFile }
        val minimapClasses = listOf(
            "xaero/hud/minimap/waypoint/render/WaypointMapRenderer.class",
            "xaero/hud/minimap/waypoint/render/WaypointMapRenderer\$Builder.class",
            "xaero/hud/minimap/waypoint/render/world/WaypointWorldRenderer.class",
            "xaero/hud/minimap/waypoint/render/world/WaypointWorldRenderer\$Builder.class"
        )
        val worldMapClasses = listOf(
            "xaero/map/mods/gui/WaypointRenderer.class",
            "xaero/map/mods/gui/WaypointRenderer\$Builder.class"
        )

        fun resolvePatchedClassBytes(relativePath: String): ByteArray {
            val remappedJar = remappedPatchJarFile.get().asFile
            if (!remappedJar.isFile) {
                error("Remapped patch jar not found: ${remappedJar.absolutePath}")
            }
            ZipFile(remappedJar).use { zip ->
                val entry = zip.getEntry(relativePath)
                    ?: error("Patched class not found in remapped jar: $relativePath")
                return zip.getInputStream(entry).readBytes()
            }
        }

        fun patchJar(jarName: String, classPaths: List<String>) {
            val patchDir = layout.buildDirectory.dir("tmp/xaero-jar-patch/$jarName").get().asFile
            patchDir.deleteRecursively()
            patchDir.mkdirs()

            classPaths.forEach { classPath ->
                val classBytes = resolvePatchedClassBytes(classPath)
                val destination = patchDir.resolve(classPath.replace('/', File.separatorChar))
                destination.parentFile.mkdirs()
                destination.writeBytes(classBytes)
            }

            val jarExecutable = File(System.getProperty("java.home"), "bin/jar").absolutePath
            val command = mutableListOf(jarExecutable, "uf", file("embedded-libs/$jarName").absolutePath)
            command.addAll(classPaths)
            val process = ProcessBuilder(command)
                .directory(patchDir)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            if (exitCode != 0) {
                error("Failed to patch $jarName with patched Xaero classes:\n$output")
            }
        }

        doLast {
            patchJar(minimapJarName, minimapClasses)
            patchJar(worldMapJarName, worldMapClasses)
        }
    }

    val cleanLibsJars by registering(Delete::class) {
        delete(fileTree(layout.buildDirectory.dir("libs")) { include("*.jar") })
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
        dependsOn(cleanLibsJars, patchEmbeddedXaeroJars)
        inputs.property("archivesName", project.base.archivesName.get())

        from(sharedMainOutput)

        from(rootProject.file("LICENSE")) {
            rename { "${it}_${inputs.properties["archivesName"]}" }
        }

        embeddedXaeroJars.forEach { (name, _, outputName) ->
            from("embedded-libs/$name") {
                into("META-INF/jars")
                rename { outputName }
            }
        }

        from(rootProject.file("chesttracker-port/chesttracker-port-embedded.jar")) {
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

    named<JavaCompile>("compileXaeroPatchJava") {
        // The xaeroPatch source-set classpath can be sparse with Loom remapped mods,
        // so explicitly piggyback main compile classpath + main outputs + remapped aerolib jars.
        classpath = sourceSets["main"].compileClasspath +
            sourceSets["main"].output
    }
}

