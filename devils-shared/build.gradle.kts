plugins {
    id("fabric-loom") version "1.14.10"
    java
}

val minecraftVersion = properties["minecraft_version"] as String

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

base {
    archivesName = "devils-shared-internal"
    version = properties["addon_version"] as String
    group = properties["maven_group"] as String
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
    mavenCentral()
}

dependencies {
    minecraft("com.mojang:minecraft:$minecraftVersion")
    mappings("net.fabricmc:yarn:${properties["yarn_mappings"] as String}:v2")
    modCompileOnly("net.fabricmc:fabric-loader:${properties["loader_version"] as String}")
    modCompileOnly("net.fabricmc.fabric-api:fabric-api:${properties["fabric_api_version"] as String}")
    modCompileOnly("meteordevelopment:meteor-client:$minecraftVersion-SNAPSHOT")
}

tasks {
    withType<JavaCompile> {
        options.encoding = "UTF-8"
        options.release = 21
        options.compilerArgs.add("-Xlint:deprecation")
        options.compilerArgs.add("-Xlint:unchecked")
    }
}
