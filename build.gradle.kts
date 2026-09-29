import org.gradle.plugin.compatibility.compatibility

plugins {
    `java-gradle-plugin`
    kotlin("jvm") version "2.3.0"
    id("com.gradle.plugin-publish") version "2.2.1"
    id("com.diffplug.spotless") version "7.2.1"
    id("io.gitlab.arturbosch.detekt") version "1.23.8"
    id("org.jetbrains.kotlinx.binary-compatibility-validator") version "0.18.1"
}

group = "io.github.savvasg28"
version = "0.1.1"

kotlin {
    jvmToolchain(17)
    // Let the functional tests use internal declarations, as the unit tests already can.
    target.compilations.configureEach {
        if (name == "functionalTest") associateWith(target.compilations.getByName("main"))
    }
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
    // License: Apache-2.0, see LICENSE
    testSourceSets(functionalTest)
    plugins {
        create("appLinks") {
            id = "io.github.savvasg28.applinks"
            implementationClass = "io.github.savvasg28.applinks.AppLinksPlugin"
            displayName = "App Links for debug builds"
            description =
                "Force-approves Android App Link domains on connected devices for debuggable variants, so https links open in the app instead of the browser on debug builds."
            tags = listOf("android", "app-links", "deep-links", "adb", "testing")
            compatibility {
                features {
                    // Every task declares its inputs and reads only providers; verified by the sample build in tests.
                    configurationCache = true
                }
            }
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

// Formatting: ktlint through Spotless. `spotlessApply` fixes, `spotlessCheck` runs on `check`.
spotless {
    kotlin {
        target("src/**/*.kt")
        ktlint("1.5.0")
        trimTrailingWhitespace()
        endWithNewline()
    }
    kotlinGradle {
        target("*.gradle.kts")
        ktlint("1.5.0")
    }
}

// Static analysis. detekt.yml only overrides the defaults it names.
detekt {
    buildUponDefaultConfig = true
    config.setFrom("detekt.yml")
    source.setFrom("src/main/kotlin", "src/test/kotlin", "src/functionalTest/kotlin")
}

// Turn Gradle's task-property validation warnings into failures.
tasks.validatePlugins {
    enableStricterValidation = true
}

// The public API is dumped to api/*.api and checked on every build; run `apiDump` after an intended change.
apiValidation {
    ignoredPackages.add("io.github.savvasg28.applinks.internal")
}
