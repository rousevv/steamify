plugins {
    id("net.fabricmc.fabric-loom") version "1.19.3"
    kotlin("jvm") version "2.4.21"
}

repositories {
    maven("https://maven.terraformersmc.com/releases")
}

dependencies {
    minecraft("com.mojang:minecraft:${property("minecraft_version")}")
    implementation("net.fabricmc:fabric-loader:${property("loader_version")}")
    implementation("net.fabricmc.fabric-api:fabric-api:${property("fabric_api_version")}")
    implementation("net.fabricmc:fabric-language-kotlin:${property("fabric_kotlin_version")}")
    // Optional: only the config button in the mod list
    compileOnly("com.terraformersmc:modmenu:${property("modmenu_version")}")
}

java.toolchain.languageVersion = JavaLanguageVersion.of(25)
kotlin.jvmToolchain(25)

tasks.processResources {
    val version = project.version
    inputs.property("version", version)
    filesMatching("fabric.mod.json") { expand("version" to version) }
}

// GPL: the license travels with the jar
tasks.jar { from("LICENSE") }
