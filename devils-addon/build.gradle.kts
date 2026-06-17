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
val gameVersionFromEnv = System.getenv("DEVILS_GAME_VERSION")
    ?.removePrefix("v")
    ?.trim()
    ?.takeIf { it.isNotEmpty() }
val gameVersionFromProperty = (findProperty("game_version_override") as String?)
    ?.removePrefix("v")
    ?.trim()
    ?.takeIf { it.isNotEmpty() }
val resolvedGameVersion = gameVersionFromEnv
    ?: gameVersionFromProperty
    ?: (properties["game_version"] as String)
val stashMoverLiveUserWorld = (findProperty("stashMoverLiveUserWorld") as String?)
    ?.trim()
    ?.equals("true", ignoreCase = true)
    ?: false
val stashMoverLiveRealistic = (findProperty("stashMoverLiveRealistic") as String?)
    ?.trim()
    ?.equals("true", ignoreCase = true)
    ?: false
val stashMoverLiveWorldName = (findProperty("stashMoverLiveWorldName") as String?)
    ?.trim()
    ?.takeIf { it.isNotEmpty() }
    ?: "Новый мир"
val gameArchivesBaseName = properties["game_archives_base_name"] as String

val minecraftVersion = properties["minecraft_version"] as String

val sourceNativeModDependencies = listOf<String>()
val remappedModCacheRoot = rootProject.file(".gradle/loom-cache/remapped_mods")
val sourceNativeBuildRoot = rootProject.file("local-source-native/Source Native Build")
val sourceNativeModuleDirs = listOf<java.io.File>()
val sourceNativePatchJavaDir = file("src/main/source-native-patches/java")
val sourceNativeBinaryJars = fileTree(remappedModCacheRoot) {
    // External mod integrations removed — no embedded JARs
    include("NEVER_MATCH_ANYTHING_DISABLED_*.jar")
}
val sourceNativeNestedJars = fileTree(sourceNativeBuildRoot) {
    // External mod nested JARs removed
}
val sourceNativeVendorJars = files()
val generatedThirdPartyNoticeDir = layout.buildDirectory.dir("generated/third-party-notices")
val generatedThirdPartyNoticeFile = generatedThirdPartyNoticeDir.map { it.file("META-INF/licenses/THIRD_PARTY_NOTICES.txt") }
val bundledRuntimeLibs by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = true
}
val sourceNativeResourceJars by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}
val mergedMixinResourceDir = "META-INF/devils-addon/mixins"
val assimilatedAccessWidenerJarPath = "META-INF/devils-addon/accesswidener/devils-addon.assimilated.accesswidener"
val sqliteJdbcResourceJarPath = "org/rfresh/sqlite/jdbc3/sqlite-jdbc.properties"
val relocatedMixinConfigs = setOf(
    "devils-addon.mixins.json"
)
val sourceNativeJavaDirs = listOf(
    file("src/main/thirdparty-audio/java"),
    sourceNativePatchJavaDir
)
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
val sourceNativeJarResourceExcludes = sourceNativeResourceExcludes + arrayOf(
    "**/*.class",
    "module-info.class"
)
val sourceNativeJavaExcludes = arrayOf(
    "com/github/benmanes/caffeine/**",
    "net/lenni0451/lambdaevents/**",
    "architectury_inject_*/**"
)
val sharedMainOutput = project(":devils-shared")
    .extensions
    .getByType(SourceSetContainer::class.java)
    .named("main")
    .map { it.output }
val sourceNativeClassOutputDir = layout.buildDirectory.dir("generated/source-native-classes/main")
val extractSourceNativeRuntimeClasses by tasks.registering(Sync::class) {
    into(sourceNativeClassOutputDir)
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    includeEmptyDirs = false

    from({
        (sourceNativeBinaryJars.files + sourceNativeVendorJars.files + sourceNativeNestedJars.files).map { dependencyArtifact ->
            zipTree(dependencyArtifact).matching {
                include("**/*.class")
                exclude("module-info.class")
            }
        }
    })
}

sourceSets.named("main") {
    sourceNativeJavaDirs.forEach { java.srcDir(it) }
    java.exclude(*sourceNativeJavaExcludes)

    resources.setSrcDirs(listOf("src/main/resources"))
    output.dir(mapOf("builtBy" to extractSourceNativeRuntimeClasses), sourceNativeClassOutputDir)
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
        create("stashMoverTargetedRuntime") {
            client()
            ideConfigGenerated(false)
            configName = "StashMover Targeted Runtime"
            runDir("run-stashmover-targeted")
            vmArg("-Ddevils.stashmover.targeted.runtime=true")
            vmArg("-Ddevils.stashmover.targeted.runtime.path=${rootProject.file("codex log/stashmover-targeted-runtime.log").absolutePath}")
        }
        create("inputRuntimeValidation") {
            client()
            ideConfigGenerated(false)
            configName = "Input Runtime Validation"
            runDir("run-input-runtime")
            vmArg("-Ddevils.strict.runtime.logging=true")
            vmArg("-Ddevils.strict.runtime.dir=${rootProject.file("codex log").absolutePath}")
            vmArg("-Ddevils.input.runtime=true")
        }
        create("autoWaspRuntimeValidation") {
            client()
            ideConfigGenerated(false)
            configName = "AutoWasp Runtime Validation"
            runDir("run-autowasp-runtime")
            vmArg("-Ddevils.strict.runtime.logging=true")
            vmArg("-Ddevils.strict.runtime.dir=${rootProject.file("codex log").absolutePath}")
            vmArg("-Ddevils.autowasp.runtime=true")
        }
        create("stashMoverStrictRuntime") {
            client()
            ideConfigGenerated(false)
            configName = "StashMover Strict Runtime"
            runDir("run-stashmover-strict")
            vmArg("-Ddevils.strict.runtime.logging=true")
            vmArg("-Ddevils.strict.runtime.dir=${rootProject.file("codex log").absolutePath}")
            vmArg("-Ddevils.stashmover.strict.runtime=true")
        }
        create("stashMoverLiveHostRuntime") {
            client()
            ideConfigGenerated(false)
            configName = "StashMover Live Host Runtime"
            runDir("run-stashmover-live-host")
            vmArg("-Ddevils.strict.runtime.logging=true")
            vmArg("-Ddevils.strict.runtime.dir=${rootProject.file("codex log").absolutePath}")
            vmArg("-Ddevils.strict.runtime.actor=host")
            vmArg("-Ddevils.stashmover.live.runtime=true")
            vmArg("-Ddevils.stashmover.live.role=host")
            if (stashMoverLiveRealistic) vmArg("-Ddevils.stashmover.live.realistic=true")
            if (stashMoverLiveUserWorld) {
                vmArg("-Ddevils.stashmover.live.userWorld=true")
                vmArg("-Ddevils.stashmover.live.worldName=$stashMoverLiveWorldName")
            }
        }
        create("stashMoverLiveGuestRuntime") {
            client()
            ideConfigGenerated(false)
            configName = "StashMover Live Guest Runtime"
            runDir("run-stashmover-live-guest")
            vmArg("-Ddevils.strict.runtime.logging=true")
            vmArg("-Ddevils.strict.runtime.dir=${rootProject.file("codex log").absolutePath}")
            vmArg("-Ddevils.strict.runtime.actor=guest")
            vmArg("-Ddevils.stashmover.live.runtime=true")
            vmArg("-Ddevils.stashmover.live.role=guest")
            if (stashMoverLiveRealistic) vmArg("-Ddevils.stashmover.live.realistic=true")
            if (stashMoverLiveUserWorld) {
                vmArg("-Ddevils.stashmover.live.userWorld=true")
                vmArg("-Ddevils.stashmover.live.worldName=$stashMoverLiveWorldName")
            }
        }
        create("nukerPlusDamageTimeRuntime") {
            client()
            ideConfigGenerated(false)
            configName = "NukerPlus Damage-Time Runtime"
            runDir("run-nukerplus-damage-time")
            vmArg("-Ddevils.nukerplus.damage.runtime=true")
            vmArg("-Ddevils.nukerplus.damage.runtime.dir=${rootProject.file("codex log").absolutePath}")
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
    // shulkerboxtooltip removed — not used in code and causes Loom build failure
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
    sourceNativeModDependencies.forEach { dependencyNotation ->
        modCompileOnly(dependencyNotation)
        testCompileOnly(dependencyNotation)
        testRuntimeOnly(dependencyNotation)
        add(sourceNativeResourceJars.name, dependencyNotation)
    }
    implementation("com.github.ben-manes.caffeine:caffeine:3.2.0")
    add(bundledRuntimeLibs.name, "com.github.ben-manes.caffeine:caffeine:3.2.0")
    implementation("net.lenni0451:LambdaEvents:2.4.2")
    add(bundledRuntimeLibs.name, "net.lenni0451:LambdaEvents:2.4.2")
    implementation("com.github.rfresh2:OldBiomes:1.0.0")
    add(bundledRuntimeLibs.name, "com.github.rfresh2:OldBiomes:1.0.0")
    implementation("org.rfresh.xerial:sqlite-jdbc:3.51.2.0")
    implementation("com.twelvemonkeys.imageio:imageio-core:3.12.0")
    add(bundledRuntimeLibs.name, "com.twelvemonkeys.imageio:imageio-core:3.12.0")
    implementation("com.twelvemonkeys.imageio:imageio-webp:3.12.0")
    add(bundledRuntimeLibs.name, "com.twelvemonkeys.imageio:imageio-webp:3.12.0")
    implementation("com.twelvemonkeys.imageio:imageio-metadata:3.12.0")
    add(bundledRuntimeLibs.name, "com.twelvemonkeys.imageio:imageio-metadata:3.12.0")
    implementation("com.twelvemonkeys.common:common-lang:3.12.0")
    add(bundledRuntimeLibs.name, "com.twelvemonkeys.common:common-lang:3.12.0")
    implementation("com.twelvemonkeys.common:common-io:3.12.0")
    add(bundledRuntimeLibs.name, "com.twelvemonkeys.common:common-io:3.12.0")
    implementation("com.twelvemonkeys.common:common-image:3.12.0")
    add(bundledRuntimeLibs.name, "com.twelvemonkeys.common:common-image:3.12.0")
    implementation("org.quiltmc.parsers:json:0.2.1")
    add(bundledRuntimeLibs.name, "org.quiltmc.parsers:json:0.2.1")
    implementation("org.quiltmc.parsers:gson:0.2.1")
    add(bundledRuntimeLibs.name, "org.quiltmc.parsers:gson:0.2.1")
    compileOnly("com.google.code.findbugs:jsr305:3.0.2")
    compileOnly(files(sourceNativeBinaryJars, sourceNativeVendorJars, sourceNativeNestedJars))
    testCompileOnly(files(sourceNativeBinaryJars, sourceNativeVendorJars, sourceNativeNestedJars))
    testRuntimeOnly(files(sourceNativeBinaryJars, sourceNativeVendorJars, sourceNativeNestedJars))

    productionRuntimeMods("meteordevelopment:meteor-client:$minecraftVersion-SNAPSHOT")
    productionRuntimeMods("net.fabricmc.fabric-api:fabric-api:${properties["fabric_api_version"] as String}")

    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks {
    val exportAddonArtifactsToRootLibs by registering(Sync::class) {
        dependsOn(remapJar, ":devils-game:remapJar")
        into(rootProject.layout.projectDirectory.dir("libs"))

        from(layout.buildDirectory.dir("libs")) {
            include("${project.base.archivesName.get()}-$resolvedAppVersion.jar")
        }

        from(project(":devils-game").layout.buildDirectory.dir("libs")) {
            include("$gameArchivesBaseName-$resolvedGameVersion.jar")
        }
    }

    val verifySourceNativeBuildBasis by registering {
        doLast {
        }
    }

    val generateThirdPartyNotices by registering {
        val soundlibsLgpl = file("src/main/thirdparty-audio/resources/META-INF/licenses/soundlibs/LGPL-2.1.txt")
        val soundlibsJorbis = file("src/main/thirdparty-audio/resources/META-INF/licenses/soundlibs/jorbis-COPYING.LIB")
        val soundlibsVorbisSpi = file("src/main/thirdparty-audio/resources/META-INF/licenses/soundlibs/vorbisspi-LICENSE.txt")

        inputs.files(listOfNotNull(
            soundlibsLgpl,
            soundlibsJorbis,
            soundlibsVorbisSpi
        ))
        outputs.file(generatedThirdPartyNoticeFile)

        doLast {
            val outputFile = generatedThirdPartyNoticeFile.get().asFile
            outputFile.parentFile.mkdirs()

            fun read(file: File): String = file.readText(StandardCharsets.UTF_8).trimEnd()
            fun readOrNotice(file: File?, title: String, body: String): String =
                file?.readText(StandardCharsets.UTF_8)?.trimEnd() ?: "$title\n\n$body".trimEnd()
            fun section(title: String, body: String): String = buildString {
                appendLine("===== $title =====")
                appendLine(body.trimEnd())
                appendLine()
            }

            val searchablesMitNotice = """
                Searchables is declared as MIT-licensed in the local source metadata.
                Source metadata path:
                - local-source-native/Source Native Build/searchables-fabric/fabric.mod.json

                The local source snapshot used for this build does not include a separate upstream LICENSE file,
                so this consolidated notice keeps the declared license identifier and source path.
            """.trimIndent()

            val yaclLgplNotice = """
                YetAnotherConfigLib is declared as LGPL-3.0-or-later in the local source metadata.
                Source metadata path:
                - local-source-native/Source Native Build/yet-another-config-lib/fabric.mod.json

                The local source snapshot used for this build does not include a separate standalone license text.
                YetAnotherConfigLib is LGPL-3.0-or-later; see the upstream repository for the full license.
            """.trimIndent()

            outputFile.writeText(
                buildString {
                    appendLine("Devils-Addon consolidated third-party notices")
                    appendLine()
                    appendLine("This build intentionally keeps a single root LICENSE for Devils-Addon and consolidates third-party notice material here.")
                    appendLine("Source-native incorporated components remain subject to their upstream license terms.")
                    appendLine()
                    append(section("Searchables - metadata notice", searchablesMitNotice))
                    append(section("YetAnotherConfigLib - metadata notice", yaclLgplNotice))
                    append(section("sqlite-jdbc - Apache-2.0", readOrNotice(null, "sqlite-jdbc license unavailable in clean checkout", "The source-native sqlite-jdbc license file was not present in this checkout; sqlite-jdbc is distributed under Apache-2.0.")))
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

        from({
            (sourceNativeResourceJars.resolve() + sourceNativeVendorJars.files + sourceNativeNestedJars.files).map { dependencyArtifact ->
                val dependencyTree =
                    if (dependencyArtifact.isDirectory) fileTree(dependencyArtifact) else zipTree(dependencyArtifact)
                dependencyTree.matching {
                    exclude(*sourceNativeJarResourceExcludes)
                }
            }
        })

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

    val verifyAssimilatedMixinResources by registering {
        dependsOn(processResources)
        val processedResourcesDir = layout.buildDirectory.dir("resources/main")
        inputs.dir(processedResourcesDir)

        doLast {
            val resourceRoot = processedResourcesDir.get().asFile
            val missingMixinConfigs = relocatedMixinConfigs
                .map { "$mergedMixinResourceDir/$it" }
                .filterNot { resourceRoot.resolve(it).isFile }

            check(missingMixinConfigs.isEmpty()) {
                "Missing relocated mixin resources: ${missingMixinConfigs.joinToString()}"
            }
        }
    }

    compileJava {
        dependsOn(verifySourceNativeBuildBasis)
    }

    named("classes") {
        dependsOn(extractSourceNativeRuntimeClasses)
        dependsOn(verifyAssimilatedMixinResources)
    }

    jar {
        inputs.property("archivesName", project.base.archivesName.get())
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE

        from(sharedMainOutput)

        from({
            bundledRuntimeLibs.resolve().map { dependencyArtifact ->
                val dependencyTree =
                    if (dependencyArtifact.isDirectory) fileTree(dependencyArtifact) else zipTree(dependencyArtifact)
                dependencyTree.matching {
                    exclude(
                        "META-INF/MANIFEST.MF",
                        "META-INF/*.SF",
                        "META-INF/*.RSA",
                        "META-INF/*.DSA",
                        "META-INF/LICENSE*",
                        "META-INF/NOTICE*",
                        "META-INF/licenses/**",
                        "META-INF/maven/**",
                        "LICENSE*",
                        "NOTICE*",
                        "COPYING*",
                        "module-info.class"
                    )
                }
            }
        })

        from({
            (sourceNativeBinaryJars.files + sourceNativeVendorJars.files + sourceNativeNestedJars.files).map { dependencyArtifact ->
                zipTree(dependencyArtifact).matching {
                    include("**/*.class")
                    exclude("module-info.class")
                }
            }
        })

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
            "config/yacl.json5",
            "devils-addon/modules.json",
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

    val validateStashMoverTargetedRuntime by registering {
        val canonicalRuntimeLog = rootProject.file("codex log/stashmover-targeted-runtime.log")

        doLast {
            check(canonicalRuntimeLog.isFile) {
                "StashMover targeted runtime log was not produced at ${canonicalRuntimeLog.absolutePath}"
            }

            val lines = canonicalRuntimeLog.readLines(StandardCharsets.UTF_8)
            check(lines.any { it.startsWith("RESULT PASS") }) {
                "StashMover targeted runtime did not report PASS. See ${canonicalRuntimeLog.absolutePath}"
            }
        }
    }

    val cleanStrictRuntimeEvidence by registering {
        val runtimeArtifacts = listOf(
            rootProject.file("codex log/runtime-main.log"),
            rootProject.file("codex log/input-runtime.log"),
            rootProject.file("codex log/autowasp-runtime.log"),
            rootProject.file("codex log/stashmover-runtime.log"),
            rootProject.file("codex log/FINAL_RUNTIME_REPORT.md")
        )

        doLast {
            runtimeArtifacts.forEach { artifact ->
                if (artifact.isFile) artifact.delete()
            }
        }
    }

    val validateInputRuntime by registering {
        val inputRuntimeLog = rootProject.file("codex log/input-runtime.log")

        doLast {
            check(inputRuntimeLog.isFile) {
                "Input runtime log was not produced at ${inputRuntimeLog.absolutePath}"
            }

            val lines = inputRuntimeLog.readLines(StandardCharsets.UTF_8)
            check(lines.any { it.contains("RESULT PASS") }) {
                "Input runtime validation did not report PASS. See ${inputRuntimeLog.absolutePath}"
            }
        }
    }

    val validateAutoWaspRuntime by registering {
        val autoWaspRuntimeLog = rootProject.file("codex log/autowasp-runtime.log")

        doLast {
            check(autoWaspRuntimeLog.isFile) {
                "AutoWasp runtime log was not produced at ${autoWaspRuntimeLog.absolutePath}"
            }

            val lines = autoWaspRuntimeLog.readLines(StandardCharsets.UTF_8)
            check(lines.any { it.contains("RESULT PASS") }) {
                "AutoWasp runtime validation did not report PASS. See ${autoWaspRuntimeLog.absolutePath}"
            }
        }
    }

    val validateStashMoverStrictRuntime by registering {
        val stashMoverRuntimeLog = rootProject.file("codex log/stashmover-runtime.log")

        doLast {
            check(stashMoverRuntimeLog.isFile) {
                "StashMover strict runtime log was not produced at ${stashMoverRuntimeLog.absolutePath}"
            }

            val lines = stashMoverRuntimeLog.readLines(StandardCharsets.UTF_8)
            check(lines.any { it.contains("RESULT PASS runs=5") }) {
                "StashMover strict runtime validation did not report 5 successful runs. See ${stashMoverRuntimeLog.absolutePath}"
            }
        }
    }

    val strictRuntimeValidation by registering {
        dependsOn(cleanStrictRuntimeEvidence)
        dependsOn("runInputRuntimeValidation")
        dependsOn("runAutoWaspRuntimeValidation")
        dependsOn("runStashMoverStrictRuntime")
    }

    val validateNukerPlusDamageTimeRuntime by registering {
        val smokeReport = rootProject.file("codex log/nukerplus-damage-time-smoke.md")
        val benchmarkReport = rootProject.file("codex log/nukerplus-damage-time-benchmark.md")
        val mechanicsReport = rootProject.file("codex log/nukerplus-damage-time-mechanics.md")

        doLast {
            check(smokeReport.isFile) {
                "NukerPlus damage smoke report was not produced at ${smokeReport.absolutePath}"
            }
            check(benchmarkReport.isFile) {
                "NukerPlus damage benchmark report was not produced at ${benchmarkReport.absolutePath}"
            }
            check(mechanicsReport.isFile) {
                "NukerPlus damage mechanics report was not produced at ${mechanicsReport.absolutePath}"
            }

            val smokeLines = smokeReport.readLines(StandardCharsets.UTF_8)
            check(smokeLines.any { it.startsWith("RESULT PASS") }) {
                "NukerPlus damage runtime smoke did not report PASS. See ${smokeReport.absolutePath}"
            }
        }
    }

    named("runAssimilatedClientSmoke") {
        doFirst {
            val smokeRunDir = layout.projectDirectory.dir("run-assimilated-smoke").asFile
            val staleEvidencePaths = listOf(
                smokeRunDir.resolve("config"),
                smokeRunDir.resolve("devils-addon"),
                smokeRunDir.resolve("logs/latest.log")
            )

            staleEvidencePaths.forEach { path ->
                if (path.isDirectory) path.deleteRecursively()
                else path.delete()
            }
        }
        finalizedBy(writeAssimilatedRuntimeEvidence)
    }

    named("runStashMoverTargetedRuntime") {
        doFirst {
            val smokeRunDir = layout.projectDirectory.dir("run-stashmover-targeted").asFile
            val staleEvidencePaths = listOf(
                smokeRunDir.resolve("saves"),
                smokeRunDir.resolve("config"),
                smokeRunDir.resolve("devils-addon"),
                smokeRunDir.resolve("logs/latest.log")
            )

            staleEvidencePaths.forEach { path ->
                if (path.isDirectory) path.deleteRecursively()
                else path.delete()
            }
        }
        finalizedBy(validateStashMoverTargetedRuntime)
    }

    named("runInputRuntimeValidation") {
        doFirst {
            val runtimeRunDir = layout.projectDirectory.dir("run-input-runtime").asFile
            val staleEvidencePaths = listOf(
                runtimeRunDir.resolve("config"),
                runtimeRunDir.resolve("devils-addon"),
                runtimeRunDir.resolve("logs/latest.log")
            )

            staleEvidencePaths.forEach { path ->
                if (path.isDirectory) path.deleteRecursively()
                else path.delete()
            }
        }
        finalizedBy(validateInputRuntime)
    }

    named("runAutoWaspRuntimeValidation") {
        doFirst {
            val runtimeRunDir = layout.projectDirectory.dir("run-autowasp-runtime").asFile
            val staleEvidencePaths = listOf(
                runtimeRunDir.resolve("saves"),
                runtimeRunDir.resolve("config"),
                runtimeRunDir.resolve("devils-addon"),
                runtimeRunDir.resolve("logs/latest.log")
            )

            staleEvidencePaths.forEach { path ->
                if (path.isDirectory) path.deleteRecursively()
                else path.delete()
            }
        }
        mustRunAfter("runInputRuntimeValidation")
        finalizedBy(validateAutoWaspRuntime)
    }

    named("runStashMoverStrictRuntime") {
        doFirst {
            val runtimeRunDir = layout.projectDirectory.dir("run-stashmover-strict").asFile
            val staleEvidencePaths = listOf(
                runtimeRunDir.resolve("saves"),
                runtimeRunDir.resolve("config"),
                runtimeRunDir.resolve("devils-addon"),
                runtimeRunDir.resolve("logs/latest.log")
            )

            staleEvidencePaths.forEach { path ->
                if (path.isDirectory) path.deleteRecursively()
                else path.delete()
            }
        }
        mustRunAfter("runAutoWaspRuntimeValidation")
        finalizedBy(validateStashMoverStrictRuntime)
    }

    named("runNukerPlusDamageTimeRuntime") {
        doFirst {
            val smokeRunDir = layout.projectDirectory.dir("run-nukerplus-damage-time").asFile
            val staleEvidencePaths = listOf(
                smokeRunDir.resolve("saves"),
                smokeRunDir.resolve("config"),
                smokeRunDir.resolve("devils-addon"),
                smokeRunDir.resolve("logs/latest.log")
            )

            staleEvidencePaths.forEach { path ->
                if (path.isDirectory) path.deleteRecursively()
                else path.delete()
            }
        }
        finalizedBy(validateNukerPlusDamageTimeRuntime)
    }

    java {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    test {
        useJUnitPlatform()
    }

    named("build") {
        dependsOn(exportAddonArtifactsToRootLibs)
    }

    withType<JavaCompile> {
        options.encoding = "UTF-8"
        options.release = 21
        options.compilerArgs.add("-Xlint:deprecation")
        options.compilerArgs.add("-Xlint:unchecked")
    }
}
