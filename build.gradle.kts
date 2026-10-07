plugins {
    java
    id("org.jetbrains.intellij.platform") version "2.9.0"
}

group = "dev.undotree"
version = "0.2.0"

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

dependencies {
    intellijPlatform {
        val localIde = providers.gradleProperty("localIdePath").orNull
        if (localIde != null) local(localIde)
        else intellijIdeaCommunity("2025.1.7")
    }
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}
tasks.withType<JavaCompile>().configureEach { options.release.set(21) }

val coreTest by tasks.registering(JavaExec::class) {
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("dev.undotree.core.CoreTests")
}
tasks.check { dependsOn(coreTest) }

intellijPlatform {
    pluginConfiguration { ideaVersion { sinceBuild.set("251") } }
    pluginVerification { ides { recommended() } }
}
