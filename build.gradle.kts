import io.gitlab.arturbosch.detekt.Detekt
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.net.URI

plugins {
    kotlin("jvm") version "1.6.21"
    `java-library`
    `maven-publish`
    jacoco

    id("io.gitlab.arturbosch.detekt").version("1.20.0")
    id("net.researchgate.release") version "2.8.1"
}

repositories {
    mavenCentral()
}

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-datetime:0.3.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.6.1")
    implementation("org.slf4j:slf4j-api:1.7.36")

    testImplementation(platform("org.junit:junit-bom:5.8.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("com.willowtreeapps.assertk:assertk-jvm:0.25")
    testImplementation("org.awaitility:awaitility-kotlin:4.2.0")
    testImplementation("org.slf4j:slf4j-simple:1.7.36")
}

group = "de.chrgroth.smartkron"

java {
    withSourcesJar()
    withJavadocJar()
}

detekt {
    buildUponDefaultConfig = true
    config = files("$projectDir/detekt-config.yaml")
}

tasks {
    withType<Detekt> {
        this.jvmTarget = "11"
    }

    withType<KotlinCompile>().configureEach {
        kotlinOptions.jvmTarget = "11"
        kotlinOptions.allWarningsAsErrors = true

        kotlinOptions.freeCompilerArgs += "-opt-in=kotlin.time.ExperimentalTime"
        kotlinOptions.freeCompilerArgs += "-opt-in=kotlin.RequiresOptIn"
    }

    test {
        finalizedBy(jacocoTestReport)
        useJUnitPlatform()
        testLogging {
            events("passed", "skipped", "failed")
        }
    }

    jacocoTestCoverageVerification {
        violationRules {
            rule {
                limit {
                    minimum = "0.90".toBigDecimal()
                }
            }
        }
    }

    check {
        dependsOn(jacocoTestCoverageVerification)
    }

    afterReleaseBuild {
        dependsOn(publish)
    }
}

publishing {

    repositories {
        maven {
            name = "artifacts.chrgroth.de"

            val repoName = if (project.version.toString().endsWith("SNAPSHOT")) "maven-snapshots" else "maven-releases"
            url = URI("https://artifacts.chrgroth.de/repository/$repoName/")

            credentials {
                username = project.property("publication_username")?.toString()
                password = project.property("publication_password")?.toString()
            }
        }
    }

    publications {
        register(project.name, MavenPublication::class) {
            from(components["java"])
        }
    }
}
