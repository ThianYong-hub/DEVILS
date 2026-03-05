plugins {
    id("fabric-loom") version "1.11-SNAPSHOT"
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
}

dependencies {
    // Fabric
    minecraft("com.mojang:minecraft:${properties["minecraft_version"] as String}")
    mappings("net.fabricmc:yarn:${properties["yarn_mappings"] as String}:v2")
    modImplementation("net.fabricmc:fabric-loader:${properties["loader_version"] as String}")

    // Meteor
    modImplementation("meteordevelopment:meteor-client:${properties["minecraft_version"] as String}-SNAPSHOT")

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
    processResources {
        val propertyMap = mapOf(
            "version" to project.version,
            "mc_version" to project.property("minecraft_version"),
        )

        inputs.properties(propertyMap)

        filteringCharset = "UTF-8"

        filesMatching("fabric.mod.json") {
            expand(propertyMap)
        }
    }

    jar {
        inputs.property("archivesName", project.base.archivesName.get())

        from("LICENSE") {
            rename { "${it}_${inputs.properties["archivesName"]}" }
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
