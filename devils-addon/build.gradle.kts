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
val gameArchivesBaseName = properties["game_archives_base_name"] as String

val minecraftVersion = properties["minecraft_version"] as String

val vendoredPatchJavaDir = file("src/main/vendored-patches/java")
val generatedThirdPartyNoticeDir = layout.buildDirectory.dir("generated/third-party-notices")
val generatedThirdPartyNoticeFile = generatedThirdPartyNoticeDir.map { it.file("META-INF/licenses/THIRD_PARTY_NOTICES.txt") }
val bundledRuntimeLibs by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = true
}
val mergedMixinResourceDir = "META-INF/devils-addon/mixins"
val assimilatedAccessWidenerJarPath = "META-INF/devils-addon/accesswidener/devils-addon.assimilated.accesswidener"
val sqliteJdbcResourceJarPath = "org/rfresh/sqlite/jdbc3/sqlite-jdbc.properties"
val relocatedMixinConfigs = setOf(
    "devils-addon.mixins.json"
)
val vendoredJavaDirs = listOf(
    file("src/main/thirdparty-audio/java"),
    vendoredPatchJavaDir
)
val vendoredResourceDirs = listOf(
    file("src/main/thirdparty-audio/resources")
)
val vendoredResourceExcludes = arrayOf(
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
    "pack.mcmeta",
    "architectury_inject_*",
    "architectury_inject_*/**"
)
val vendoredJavaExcludes = arrayOf(
    "com/github/benmanes/caffeine/**",
    "net/lenni0451/lambdaevents/**",
    "architectury_inject_*/**"
)
val sharedMainOutput = project(":devils-shared")
    .extensions
    .getByType(SourceSetContainer::class.java)
    .named("main")
    .map { it.output }
sourceSets.named("main") {
    vendoredJavaDirs.forEach { java.srcDir(it) }
    java.exclude(*vendoredJavaExcludes)

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
}

dependencies {
    minecraft("com.mojang:minecraft:$minecraftVersion")
    mappings("net.fabricmc:yarn:${properties["yarn_mappings"] as String}:v2")
    modImplementation("net.fabricmc:fabric-loader:${properties["loader_version"] as String}")
    modImplementation("net.fabricmc.fabric-api:fabric-api:${properties["fabric_api_version"] as String}")

    modImplementation("meteordevelopment:meteor-client:$minecraftVersion-SNAPSHOT")
    implementation(project(":devils-shared"))

    modImplementation("io.github.llamalad7:mixinextras-fabric:0.5.0")

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

            outputFile.writeText(
                buildString {
                    appendLine("Devils-Addon consolidated third-party notices")
                    appendLine()
                    appendLine("This build intentionally keeps a single root LICENSE for Devils-Addon and consolidates third-party notice material here.")
                    appendLine("Vendored components remain subject to their upstream license terms.")
                    appendLine()
                    append(section("sqlite-jdbc - Apache-2.0", readOrNotice(null, "sqlite-jdbc license unavailable in clean checkout", "The sqlite-jdbc license file was not present in this checkout; sqlite-jdbc is distributed under Apache-2.0.")))
                    append(section("Soundlibs - LGPL-2.1", read(soundlibsLgpl)))
                    append(section("Soundlibs - jorbis notice", read(soundlibsJorbis)))
                    append(section("Soundlibs - vorbisspi notice", read(soundlibsVorbisSpi)))
                },
                StandardCharsets.UTF_8
            )
        }
    }

    processResources {
        dependsOn(generateThirdPartyNotices)
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE

        val propertyMap = mapOf(
            "version" to project.version,
            "mc_version" to minecraftVersion
        )

        inputs.properties(propertyMap)
        filteringCharset = "UTF-8"

        from(vendoredResourceDirs) {
            exclude(*vendoredResourceExcludes)
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

    named("classes") {
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

        from(rootProject.file("LICENSE")) {
            into("META-INF/licenses")
            rename { "DEVILS-ADDON_LICENSE.txt" }
        }
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
