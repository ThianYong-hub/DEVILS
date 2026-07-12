plugins {
    base
}

group = properties["maven_group"] as String
version = properties["addon_version"] as String

fun resolvedVersion(envName: String, propertyName: String, fallbackName: String): String =
    System.getenv(envName)
        ?.removePrefix("v")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: (findProperty(propertyName) as String?)
            ?.removePrefix("v")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        ?: (properties[fallbackName] as String)

val addonVersion = resolvedVersion("DEVILS_ADDON_VERSION", "addon_version_override", "addon_version")
val gameVersion = resolvedVersion("DEVILS_GAME_VERSION", "game_version_override", "game_version")

val collectReleaseArtifacts by tasks.registering(Sync::class) {
    dependsOn(":devils-addon:build", ":devils-game:build")
    into(layout.buildDirectory.dir("libs"))

    from(project(":devils-addon").layout.buildDirectory.dir("libs")) {
        include("devils-addon-$addonVersion.jar")
    }

    from(project(":devils-game").layout.buildDirectory.dir("libs")) {
        include("devils-game-$gameVersion.jar")
    }
}

tasks.named("assemble") {
    dependsOn(collectReleaseArtifacts)
}

tasks.named("build") {
    dependsOn(":devils-shared:build", ":devils-addon:build", ":devils-game:build", collectReleaseArtifacts)
}

tasks.named("clean") {
    dependsOn(":devils-shared:clean", ":devils-addon:clean", ":devils-game:clean")
}
