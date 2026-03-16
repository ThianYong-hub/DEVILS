plugins {
    id("fabric-loom") version "1.14-SNAPSHOT"
    java
}

group = (findProperty("maven_group") as String?) ?: "com.example"
version = "2.8.1-devils-1.21.11"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
    withSourcesJar()
}

base {
    archivesName.set("devils-addon-chesttracker")
}

loom {
    accessWidenerPath.set(file("src/main/resources/chesttracker.accesswidener"))
}

repositories {
    maven {
        name = "ParchmentMC"
        url = uri("https://maven.parchmentmc.org")
        content {
            includeGroup("org.parchmentmc.data")
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
        name = "Modrinth Maven"
        url = uri("https://api.modrinth.com/maven")
        content {
            includeGroup("maven.modrinth")
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
        name = "Shedaniel"
        url = uri("https://maven.shedaniel.me")
        content {
            includeGroupAndSubgroups("me.shedaniel")
        }
    }

    maven {
        name = "WTHIT"
        url = uri("https://maven2.bai.lol")
        content {
            includeGroupAndSubgroups("lol.bai")
            includeGroupAndSubgroups("mcp.mobius.waila")
        }
    }
}

dependencies {
    minecraft("com.mojang:minecraft:${project.property("minecraft_version")}")
    mappings(loom.layered {
        officialMojangMappings()
        parchment("org.parchmentmc.data:parchment-1.21.11:2025.12.20@zip")
    })

    modImplementation("net.fabricmc:fabric-loader:${project.property("loader_version")}")
    modImplementation("net.fabricmc.fabric-api:fabric-api:0.140.0+1.21.11")

    modImplementation("maven.modrinth:where-is-it-port:2.7.3+1.21.11")
    include("maven.modrinth:where-is-it-port:2.7.3+1.21.11")

    modImplementation("dev.isxander:yet-another-config-lib:3.8.1+1.21.11-fabric") {
        exclude(group = "com.terraformersmc", module = "modmenu")
    }
    include("dev.isxander:yet-another-config-lib:3.8.1+1.21.11-fabric")

    modCompileOnly("com.blamejared.searchables:Searchables-fabric-1.21.11:1.0.2") {
        exclude(group = "net.fabricmc.fabric-api", module = "fabric-api")
    }
    include("com.blamejared.searchables:Searchables-fabric-1.21.11:1.0.2")

    modCompileOnly("com.terraformersmc:modmenu:17.0.0-beta.1")
    modCompileOnly("com.misterpemodder:shulkerboxtooltip-fabric:5.2.14+1.21.11")
    modCompileOnly("mcp.mobius.waila:wthit-api:fabric-18.0.4")
    modCompileOnly("maven.modrinth:jade:21.0.1+fabric")
    modCompileOnly("maven.modrinth:litematica:0.25.2")
    modCompileOnly("maven.modrinth:malilib:0.27.2")
    modCompileOnly(fileTree("libs") {
        include("jackfredlib-*.jar")
        include("jackfredlib-deps/*.jar")
    })
}

tasks.processResources {
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

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
    options.encoding = "UTF-8"
}

tasks.jar {
    val license = file("../LICENSE")
    if (license.exists()) {
        from(license) {
            rename { "${it}_${base.archivesName.get()}" }
        }
    }
}
