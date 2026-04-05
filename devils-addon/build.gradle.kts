import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.tasks.SourceSetContainer
import java.nio.charset.StandardCharsets

plugins {
    id("fabric-loom") version "1.14.10"
    java
}

evaluationDependsOn(":devils-shared")

val appVersionFromEnv = System.getenv("DEVILS_ADDON_VERSION")
    ?.removePrefix("v")
    ?.trim()
    ?.takeIf { it.isNotEmpty() }
val appVersionFromProperty = (findProperty("addon_version_override") as String?)
    ?.removePrefix("v")
    ?.trim()
    ?.takeIf { it.isNotEmpty() }
val modVersionFallback = properties["addon_version"] as String
val resolvedAppVersion = appVersionFromEnv
    ?: appVersionFromProperty
    ?: modVersionFallback

val minecraftVersion = properties["minecraft_version"] as String
val xaeroMinimapVersion = properties["xaero_minimap_version"] as String
val xaeroWorldMapVersion = properties["xaero_worldmap_version"] as String
val xaeroPlusVersion = properties["xaeroplus_version"] as String
val sourceNativeBuildRoot = rootProject.file("Souce 1.21.11/Source Native Build")
val sourceNativeModuleDirs = listOf(
    "chesttracker-port-embedded",
    "searchables-fabric",
    "where-is-it-port",
    "xaerolib-fabric",
    "xaeroplus-fabric",
    "xaeros-minimap-fabric",
    "xaeros-world-map-fabric",
    "yet-another-config-lib"
).map { sourceNativeBuildRoot.resolve(it) }
val sourceNativePatchJavaDir = file("src/main/source-native-patches/java")
val generatedThirdPartyNoticeDir = layout.buildDirectory.dir("generated/third-party-notices")
val generatedThirdPartyNoticeFile = generatedThirdPartyNoticeDir.map { it.file("META-INF/licenses/THIRD_PARTY_NOTICES.txt") }
val mergedMixinResourceDir = "META-INF/devils-addon/mixins"
val assimilatedAccessWidenerJarPath = "META-INF/devils-addon/accesswidener/devils-addon.assimilated.accesswidener"
val sqliteJdbcResourceJarPath = "org/rfresh/sqlite/jdbc3/sqlite-jdbc.properties"
val relocatedMixinConfigs = setOf(
    "addon-template.mixins.json",
    "chesttracker.mixins.json",
    "whereisit.mixins.json",
    "searchables.mixins.json",
    "searchables.fabric.mixins.json",
    "yacl.mixins.json",
    "yacl-fabric.mixins.json",
    "xaerolib.mixins.json",
    "xaerolib.fabric.mixins.json",
    "xaerohud.mixins.json",
    "xaerohud.fabric.mixins.json",
    "xaerominimap.mixins.json",
    "xaerominimap.fabric.mixins.json",
    "xaeroworldmap.mixins.json",
    "xaeroworldmap.fabric.mixins.json",
    "xaeroplus.mixins.json",
    "xaeroplus-fabric.mixins.json"
)
val sourceNativeJavaDirs = listOf(
    file("src/main/thirdparty-audio/java"),
    sourceNativePatchJavaDir
) + sourceNativeModuleDirs
val sourceNativeResourceDirs = listOf(
    file("src/main/thirdparty-audio/resources")
) + sourceNativeModuleDirs
val sourceNativeResourceExcludes = arrayOf(
    "**/*.java",
    "fabric.mod.json",
    "**/*.accesswidener",
    "META-INF/MANIFEST.MF",
    "META-INF/*.SF",
    "META-INF/*.RSA",
    "META-INF/*.DSA",
    "META-INF/jars/**",
    "META-INF/maven/**",
    "META-INF/LICENSE*",
    "META-INF/NOTICE*",
    "META-INF/licenses/**",
    "LICENSE*",
    "NOTICE*",
    "COPYING*",
    "icon.png",
    "yacl-128x.png",
    "pack.mcmeta",
    "architectury_inject_*",
    "architectury_inject_*/**"
)
val sourceNativeJavaExcludes = arrayOf(
    "dev/isxander/yacl3/mixin/MinecraftMixin.java",
    "red/jackf/whereisit/client/compat/recipeviewers/**",
    "red/jackf/whereisit/command/CommandCriteria.java",
    "com/github/benmanes/caffeine/**",
    "net/lenni0451/lambdaevents/**",
    "xaero/common/mods/SupportAmecs.java",
    "xaero/common/mixin/MixinFabricBatchableBufferSource.java",
    "xaero/common/server/mods/argonauts/**",
    "xaero/common/server/mods/ftbteams/**",
    "xaero/map/mods/SupportAmecs.java",
    "xaero/map/server/mods/argonauts/**",
    "xaero/map/server/mods/ftbteams/**",
    "xaero/lib/client/compat/prometheus/**",
    "xaero/lib/common/compat/prometheus/**",
    "xaero/lib/common/compat/ftbranks/**",
    "xaero/lib/common/compat/luckperms/**",
    "xaero/lib/common/compat/permissionapi/**",
    "architectury_inject_*/**",
)
val sharedMainOutput = project(":devils-shared")
    .extensions
    .getByType(SourceSetContainer::class.java)
    .named("main")
    .map { it.output }

sourceSets.named("main") {
    sourceNativeJavaDirs.forEach { java.srcDir(it) }
    java.exclude(*sourceNativeJavaExcludes)

    resources.setSrcDirs(listOf("src/main/resources"))
}

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
    accessWidenerPath = file("src/main/resources/devils-addon.assimilated.accesswidener")
    runs {
        create("assimilatedClientSmoke") {
            client()
            ideConfigGenerated(false)
            configName = "Assimilated Client Smoke"
            runDir("run-assimilated-smoke")
            vmArg("-Ddevils.assimilated.quality.smoke=true")
            vmArg("-Ddevils.runtime.smoke.path=${rootProject.file("codex log/runtime-smoke.log").absolutePath}")
        }
    }
}

repositories {
    mavenCentral()
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
        name = "2b2t Releases"
        url = uri("https://maven.2b2t.vc/releases")
    }
    maven {
        name = "2b2t Remote"
        url = uri("https://maven.2b2t.vc/remote")
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
        name = "Shedaniel"
        url = uri("https://maven.shedaniel.me/")
    }
    maven {
        name = "CurseMaven"
        url = uri("https://cursemaven.com")
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
    maven {
        name = "JackFredMaven"
        url = uri("https://maven.jackf.red/releases/")
        content {
            includeGroupAndSubgroups("red.jackf.jackfredlib")
        }
    }
}

dependencies {
    minecraft("com.mojang:minecraft:$minecraftVersion")
    mappings("net.fabricmc:yarn:${properties["yarn_mappings"] as String}:v2")
    modImplementation("net.fabricmc:fabric-loader:${properties["loader_version"] as String}")
    modImplementation("net.fabricmc.fabric-api:fabric-api:${properties["fabric_api_version"] as String}")

    modImplementation("meteordevelopment:meteor-client:$minecraftVersion-SNAPSHOT")
    implementation(project(":devils-shared"))

    modImplementation("io.github.llamalad7:mixinextras-fabric:0.5.0")

    modCompileOnly("com.terraformersmc:modmenu:17.0.0-beta.1")
    modCompileOnly("com.misterpemodder:shulkerboxtooltip-fabric:5.2.14+1.21.11")
    modCompileOnly("mcp.mobius.waila:wthit-api:fabric-18.0.4")
    modCompileOnly("maven.modrinth:jade:21.0.1+fabric")
    modCompileOnly("maven.modrinth:litematica:0.25.2")
    modCompileOnly("maven.modrinth:malilib:0.27.2")
    modCompileOnly("maven.modrinth:open-parties-and-claims:fabric-1.20.1-0.24.0")
    modCompileOnly("maven.modrinth:sodium:mc1.21.11-0.8.0-fabric")
    modCompileOnly("maven.modrinth:waystones:14.1.17+fabric-1.20.1")
    modCompileOnly("maven.modrinth:balm:7.3.35+fabric-1.20.1")
    modCompileOnly("maven.modrinth:fwaystones:3.3.3+mc1.20.1")
    modCompileOnly("maven.modrinth:worldtools:1.2.4+1.20.1")
    modCompileOnly("maven.modrinth:immediatelyfast:1.5.2+1.20.4-fabric")
    modCompileOnly("meteordevelopment:baritone:1.21.10-SNAPSHOT")
    implementation("com.github.ben-manes.caffeine:caffeine:3.2.0")
    implementation("net.lenni0451:LambdaEvents:2.4.2")
    implementation("com.github.rfresh2:OldBiomes:1.0.0")
    implementation("org.rfresh.xerial:sqlite-jdbc:3.51.2.0")
    implementation("com.twelvemonkeys.imageio:imageio-core:3.12.0")
    implementation("com.twelvemonkeys.imageio:imageio-webp:3.12.0")
    implementation("com.twelvemonkeys.imageio:imageio-metadata:3.12.0")
    implementation("com.twelvemonkeys.common:common-lang:3.12.0")
    implementation("com.twelvemonkeys.common:common-io:3.12.0")
    implementation("com.twelvemonkeys.common:common-image:3.12.0")
    implementation("org.quiltmc.parsers:json:0.2.1")
    implementation("org.quiltmc.parsers:gson:0.2.1")
    compileOnly("com.google.code.findbugs:jsr305:3.0.2")

    productionRuntimeMods("meteordevelopment:meteor-client:$minecraftVersion-SNAPSHOT")
    productionRuntimeMods("net.fabricmc.fabric-api:fabric-api:${properties["fabric_api_version"] as String}")

    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks {
    val verifySourceNativeBuildBasis by registering {
        doLast {
            val requiredPaths = listOf(
                sourceNativeBuildRoot.resolve("chesttracker-port-embedded/red/jackf/chesttracker/impl/ChestTracker.java"),
                sourceNativeBuildRoot.resolve("where-is-it-port/red/jackf/whereisit/WhereIsIt.java"),
                sourceNativeBuildRoot.resolve("xaerolib-fabric/xaero/lib/XaeroLibFabric.java"),
                sourceNativeBuildRoot.resolve("xaeros-minimap-fabric/xaero/minimap/XaeroMinimapFabric.java"),
                sourceNativeBuildRoot.resolve("xaeros-world-map-fabric/xaero/map/WorldMapFabric.java"),
                sourceNativeBuildRoot.resolve("xaeroplus-fabric/xaeroplus/fabric/XaeroPlusFabric.java"),
                sourceNativeBuildRoot.resolve("yet-another-config-lib/dev/isxander/yacl3/platform/PlatformEntrypoint.java"),
                sourceNativeBuildRoot.resolve("searchables-fabric/searchables.mixins.json"),
                sourceNativePatchJavaDir.resolve("red/jackf/jackfredlib/api/base/ResultHolder.java"),
                sourceNativePatchJavaDir.resolve("red/jackf/jackfredlib/client/api/gps/Coordinate.java"),
                sourceNativePatchJavaDir.resolve("red/jackf/jackfredlib/client/api/toasts/CustomToast.java"),
                sourceNativeBuildRoot.resolve("xaeros-minimap-fabric/xaero/common/server/mods/SupportServerMods.java"),
                sourceNativeBuildRoot.resolve("xaerolib-fabric/xaero/lib/client/compat/amecs/AmecsCompatibility.java")
            )
            requiredPaths.forEach { path ->
                check(path.exists()) {
                    "Missing source-native build input: ${path.absolutePath}"
                }
            }
        }
    }

    val generateThirdPartyNotices by registering {
        val chestTrackerLicense = rootProject.file("Souce 1.21.11/Source Github/ChestTracker-v2.8.1+1.21.11/LICENSE")
        val whereIsItLicense = sourceNativeBuildRoot.resolve("where-is-it-port/LICENSE_null")
        val xaeroHudNotice = sourceNativeBuildRoot.resolve("xaeros-minimap-fabric/LICENSE_xaerohud")
        val xaeroPlusLicense = rootProject.file("Souce 1.21.11/Source Github/XaeroPlus-2.30.9/LICENSE")
        val sqliteLicense = sourceNativeBuildRoot.resolve("xaeroplus-fabric/META-INF/LICENSE")
        val soundlibsLgpl = file("src/main/thirdparty-audio/resources/META-INF/licenses/soundlibs/LGPL-2.1.txt")
        val soundlibsJorbis = file("src/main/thirdparty-audio/resources/META-INF/licenses/soundlibs/jorbis-COPYING.LIB")
        val soundlibsVorbisSpi = file("src/main/thirdparty-audio/resources/META-INF/licenses/soundlibs/vorbisspi-LICENSE.txt")

        inputs.files(
            chestTrackerLicense,
            whereIsItLicense,
            xaeroHudNotice,
            xaeroPlusLicense,
            sqliteLicense,
            soundlibsLgpl,
            soundlibsJorbis,
            soundlibsVorbisSpi
        )
        outputs.file(generatedThirdPartyNoticeFile)

        doLast {
            val outputFile = generatedThirdPartyNoticeFile.get().asFile
            outputFile.parentFile.mkdirs()

            fun read(file: File): String = file.readText(StandardCharsets.UTF_8).trimEnd()
            fun section(title: String, body: String): String = buildString {
                appendLine("===== $title =====")
                appendLine(body.trimEnd())
                appendLine()
            }

            val searchablesMitNotice = """
                Searchables is declared as MIT-licensed in the local source metadata.
                Source metadata path:
                - Souce 1.21.11/Source Native Build/searchables-fabric/fabric.mod.json

                The local source snapshot used for this build does not include a separate upstream LICENSE file,
                so this consolidated notice keeps the declared license identifier and source path.
            """.trimIndent()

            val yaclLgplNotice = """
                YetAnotherConfigLib is declared as LGPL-3.0-or-later in the local source metadata.
                Source metadata path:
                - Souce 1.21.11/Source Native Build/yet-another-config-lib/fabric.mod.json

                The local source snapshot used for this build does not include a separate standalone license text,
                so the LGPL-3.0 text bundled below for the JackFred/ChestTracker/WhereIsIt family also covers this component.
            """.trimIndent()

            val xaeroNotice = """
                XaeroLib, Xaero's Minimap and Xaero's World Map are marked "All Rights Reserved"
                in the local source metadata used for this build.

                Metadata paths:
                - Souce 1.21.11/Source Native Build/xaerolib-fabric/fabric.mod.json
                - Souce 1.21.11/Source Native Build/xaeros-minimap-fabric/fabric.mod.json
                - Souce 1.21.11/Source Native Build/xaeros-world-map-fabric/fabric.mod.json

                Bundled notice text:
                ${read(xaeroHudNotice)}
            """.trimIndent()

            outputFile.writeText(
                buildString {
                    appendLine("Devils-Addon consolidated third-party notices")
                    appendLine()
                    appendLine("This build intentionally keeps a single root LICENSE for Devils-Addon and consolidates third-party notice material here.")
                    appendLine("Source-native incorporated components remain subject to their upstream license terms.")
                    appendLine()
                    append(section("ChestTracker, JackFredLib, WhereIsIt family - LGPL-3.0", read(chestTrackerLicense)))
                    append(section("WhereIsIt bundled license text as found in local source basis", read(whereIsItLicense)))
                    append(section("Searchables - metadata notice", searchablesMitNotice))
                    append(section("YetAnotherConfigLib - metadata notice", yaclLgplNotice))
                    append(section("Xaero family notice", xaeroNotice))
                    append(section("XaeroPlus - MIT", read(xaeroPlusLicense)))
                    append(section("sqlite-jdbc payload inside source-native XaeroPlus tree - Apache-2.0", read(sqliteLicense)))
                    append(section("Soundlibs - LGPL-2.1", read(soundlibsLgpl)))
                    append(section("Soundlibs - jorbis notice", read(soundlibsJorbis)))
                    append(section("Soundlibs - vorbisspi notice", read(soundlibsVorbisSpi)))
                },
                StandardCharsets.UTF_8
            )
        }
    }

    processResources {
        dependsOn(verifySourceNativeBuildBasis)
        dependsOn(generateThirdPartyNotices)
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE

        val propertyMap = mapOf(
            "version" to project.version,
            "mc_version" to minecraftVersion
        )

        inputs.properties(propertyMap)
        filteringCharset = "UTF-8"

        from(sourceNativeResourceDirs) {
            exclude(*sourceNativeResourceExcludes)
        }

        from(generatedThirdPartyNoticeDir)

        filesMatching(relocatedMixinConfigs.toList()) {
            path = "$mergedMixinResourceDir/$name"
        }

        filesMatching("devils-addon.assimilated.accesswidener") {
            path = assimilatedAccessWidenerJarPath
        }

        filesMatching("sqlite-jdbc.properties") {
            path = sqliteJdbcResourceJarPath
        }

        filesMatching("fabric.mod.json") {
            expand(propertyMap)
        }
    }

    compileJava {
        dependsOn(verifySourceNativeBuildBasis)
    }

    jar {
        inputs.property("archivesName", project.base.archivesName.get())

        from(sharedMainOutput)

        from(rootProject.file("LICENSE")) {
            into("META-INF/licenses")
            rename { "DEVILS-ADDON_LICENSE.txt" }
        }
    }

    val writeAssimilatedRuntimeEvidence by registering {
        val canonicalRuntimeLog = rootProject.file("codex log/runtime-smoke.log")
        val smokeRunDir = layout.projectDirectory.dir("run-assimilated-smoke").asFile
        val latestClientLog = smokeRunDir.resolve("logs/latest.log")
        val runtimeArtifacts = listOf(
            "config/chesttracker.json5",
            "config/whereisit.json5",
            "config/yacl.json5",
            "config/xaero/minimap/client.cfg",
            "config/xaero/world-map/client.cfg",
            "config/xaero/lib/client.cfg",
            "devils-addon/modules.json",
            "devils-addon/chesttracker/module-settings.json"
        )

        doLast {
            val parentDir = canonicalRuntimeLog.parentFile
            if (parentDir != null) parentDir.mkdirs()

            val lines = if (latestClientLog.isFile) latestClientLog.readLines(StandardCharsets.UTF_8) else emptyList()
            val existingHarnessLines = if (canonicalRuntimeLog.isFile) canonicalRuntimeLog.readLines(StandardCharsets.UTF_8) else emptyList()
            val harnessEvidence = existingHarnessLines.filter { line ->
                line.startsWith("SUMMARY ")
                    || line.startsWith("RUNTIME ")
                    || line.startsWith("PASS ")
                    || line.startsWith("FAIL ")
                    || line.startsWith("RESULT ")
            }
            val extracted = lines.filter { line ->
                line.contains("Loading Minecraft")
                    || (line.contains("Loading ") && line.contains(" mods:"))
                    || line.contains("- devils-addon ")
                    || line.contains("Initializing Devils Addon")
                    || line.contains("Initializing Meteor Client")
                    || line.contains("Loading Xaero's World Map - Stage 2/2")
                    || line.contains("Sound engine started")
                    || line.contains("Stopping!")
            }
            val requiredPatterns = listOf(
                "Loading Minecraft",
                "Initializing Meteor Client",
                "Sound engine started",
                "Stopping!"
            )
            val missingPatterns = requiredPatterns.filter { pattern -> extracted.none { it.contains(pattern) } }
            val artifactStates = runtimeArtifacts.associateWith { relative ->
                smokeRunDir.resolve(relative).isFile
            }
            val addonModLoaded = extracted.any { it.contains("- devils-addon ") || it.contains("Initializing Devils Addon") }
            val harnessPass = harnessEvidence.any { it.startsWith("RESULT PASS") }
            val resultPass = latestClientLog.isFile
                && missingPatterns.isEmpty()
                && addonModLoaded
                && artifactStates.values.all { it }
                && harnessPass

            canonicalRuntimeLog.writeText(
                buildString {
                    appendLine("SUMMARY runtime-smoke canonical-client-evidence")
                    appendLine("runDir=${smokeRunDir.absolutePath}")
                    appendLine("latestLog=${latestClientLog.absolutePath}")
                    appendLine("result=" + if (resultPass) "PASS" else "FAIL")
                    appendLine("clientLogPresent=${latestClientLog.isFile}")
                    appendLine("addonModLoaded=$addonModLoaded")
                    appendLine("harnessPass=$harnessPass")
                    artifactStates.forEach { (relative, present) ->
                        appendLine("ARTIFACT $relative present=$present")
                    }
                    if (missingPatterns.isNotEmpty()) {
                        missingPatterns.forEach { pattern ->
                            appendLine("MISSING pattern=$pattern")
                        }
                    }
                    if (harnessEvidence.isEmpty()) {
                        appendLine("MISSING harnessEvidence=true")
                    }
                    extracted.forEach { line ->
                        appendLine("LOG $line")
                    }
                    harnessEvidence.forEach { line ->
                        appendLine("HARNESS $line")
                    }
                },
                StandardCharsets.UTF_8
            )

            check(resultPass) {
                "Runtime smoke evidence was incomplete. Missing patterns: $missingPatterns artifactStates=$artifactStates addonModLoaded=$addonModLoaded harnessPass=$harnessPass"
            }
        }
    }

    named("runAssimilatedClientSmoke") {
        doFirst {
            val smokeRunDir = layout.projectDirectory.dir("run-assimilated-smoke").asFile
            val staleEvidencePaths = listOf(
                smokeRunDir.resolve("config"),
                smokeRunDir.resolve("devils-addon"),
                smokeRunDir.resolve("xaero"),
                smokeRunDir.resolve("logs/latest.log")
            )

            staleEvidencePaths.forEach { path ->
                if (path.isDirectory) path.deleteRecursively()
                else path.delete()
            }
        }
        finalizedBy(writeAssimilatedRuntimeEvidence)
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
