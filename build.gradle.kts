plugins {
    `java-gradle-plugin`
    kotlin("jvm") version "2.3.0"
    id("com.gradle.plugin-publish") version "1.3.1"
}

group = "io.github.savvasg28"
version = "0.1.0-SNAPSHOT"

kotlin {
    jvmToolchain(17)
}

val functionalTest: SourceSet by sourceSets.creating

configurations[functionalTest.implementationConfigurationName].extendsFrom(configurations.testImplementation.get())
configurations[functionalTest.runtimeOnlyConfigurationName].extendsFrom(configurations.testRuntimeOnly.get())

dependencies {
    compileOnly("com.android.tools.build:gradle-api:8.7.3")

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    functionalTest.implementationConfigurationName(gradleTestKit())
    functionalTest.implementationConfigurationName(sourceSets.main.get().output)
}

gradlePlugin {
    website = "https://github.com/savvasg28/applinks-gradle-plugin"
    vcsUrl = "https://github.com/savvasg28/applinks-gradle-plugin"
    testSourceSets(functionalTest)
    plugins {
        create("appLinks") {
            id = "io.github.savvasg28.applinks"
            implementationClass = "io.github.savvasg28.applinks.AppLinksPlugin"
            displayName = "App Links for debug builds"
            description = "Force-approves Android App Link domains on connected devices for debuggable variants, so https links open in the app instead of the browser on debug builds."
            tags = listOf("android", "app-links", "deep-links", "adb", "testing")
        }
    }
}

tasks.register<Test>("functionalTest") {
    description = "Runs Gradle TestKit tests against a sample Android project."
    group = "verification"
    testClassesDirs = functionalTest.output.classesDirs
    classpath = functionalTest.runtimeClasspath
    useJUnitPlatform()
    shouldRunAfter(tasks.test)
}

tasks.test {
    useJUnitPlatform()
}

tasks.check {
    dependsOn("functionalTest")
}
