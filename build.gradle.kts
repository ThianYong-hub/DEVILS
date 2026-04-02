plugins {
    base
}

group = properties["maven_group"] as String
version = properties["addon_version"] as String

val collectReleaseArtifacts by tasks.registering(Sync::class) {
    dependsOn(":devils-addon:build", ":devils-game:build")
    into(layout.buildDirectory.dir("libs"))

    from(project(":devils-addon").layout.buildDirectory.dir("libs")) {
        include("devils-addon-*.jar")
    }

    from(project(":devils-game").layout.buildDirectory.dir("libs")) {
        include("devils-game-*.jar")
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
