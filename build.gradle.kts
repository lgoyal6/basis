plugins {
    java
    id("org.springframework.boot") version "3.5.16"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.basis"
version = "0.1.0-SNAPSHOT"
description = "Independently recomputes brokerage positions from transaction history"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

extra["jqwikVersion"] = "1.9.3"
extra["archunitVersion"] = "1.4.1"

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.flywaydb:flyway-core")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.postgresql:postgresql")
    testImplementation("org.flywaydb:flyway-database-postgresql")
    testImplementation("net.jqwik:jqwik:${property("jqwikVersion")}")
    testImplementation("com.tngtech.archunit:archunit-junit5:${property("archunitVersion")}")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.addAll(listOf("-parameters", "-Xlint:all"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform {
        // -PexcludeTags=week3 drops the invariant 8 placeholder, which fails on purpose.
        // Useful for a CI gate; the default run keeps it red so the gap stays visible.
        val excluded = (findProperty("excludeTags") as String?)
            ?.split(",")
            ?.map(String::trim)
            ?.filter(String::isNotEmpty)
            .orEmpty()
            .toMutableList()
        // Tests that call the market data provider are off unless asked for with
        // -PwithNetwork. They spend real quota and they fail when someone is offline,
        // neither of which should happen on an ordinary run.
        if (!project.hasProperty("withNetwork")) {
            excluded += "network"
        }
        if (excluded.isNotEmpty()) {
            excludeTags(*excluded.toTypedArray())
        }
    }
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

// basis is a batch/ledger process, not a service. No executable jar is needed yet.
tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    enabled = false
}
tasks.named<Jar>("jar") {
    enabled = true
}
