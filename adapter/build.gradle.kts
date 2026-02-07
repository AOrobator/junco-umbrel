import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.9.23"
    kotlin("plugin.serialization") version "1.9.23"
    application
    jacoco
}

group = "com.junco"
version = "0.1.0"

repositories {
    mavenCentral()
    maven { url = uri("https://code.sparrowwallet.com/api/packages/sparrowwallet/maven") }
}

val ktorVersion = "2.3.8"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(22))
    }
}

jacoco {
    toolVersion = "0.8.12"
}

application {
    mainClass.set("com.junco.adapter.MainKt")
    applicationName = "junco-adapter"
}

dependencies {
    implementation("io.ktor:ktor-server-core-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-netty-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation-jvm:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-call-logging-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-sessions-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-status-pages-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-default-headers-jvm:$ktorVersion")

    implementation("ch.qos.logback:logback-classic:1.5.18")

    implementation("com.sparrowwallet:sparrow:2.3.2") {
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-javafx")
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core")
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core-jvm")
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-jdk8")
    }
    implementation("com.sparrowwallet:drongo:2.3.2")
    implementation("com.sparrowwallet:lark:2.3.2")
    implementation("com.google.guava:guava:33.5.0-jre")
    implementation("com.google.code.gson:gson:2.9.1")
    implementation("com.github.arteam:simple-json-rpc-core:1.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-jdk8:1.7.1")

    testImplementation("io.ktor:ktor-server-test-host-jvm:$ktorVersion")
    testImplementation("io.ktor:ktor-client-content-negotiation-jvm:$ktorVersion")
    testImplementation("io.mockk:mockk:1.13.11")
    testImplementation(kotlin("test"))
}

tasks.withType<KotlinCompile>().configureEach {
    kotlinOptions {
        jvmTarget = "21"
        freeCompilerArgs += listOf("-Xjsr305=strict")
    }
}

tasks.test {
    useJUnitPlatform()
    finalizedBy("jacocoTestReport")
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.test)
    violationRules {
        rule {
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = "0.90".toBigDecimal()
            }
            limit {
                counter = "BRANCH"
                value = "COVEREDRATIO"
                minimum = "0.90".toBigDecimal()
            }
        }
    }
}

tasks.check {
    dependsOn(tasks.jacocoTestCoverageVerification)
}
