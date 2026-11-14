import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("org.springframework.boot") version "3.3.1"
    id("io.spring.dependency-management") version "1.1.5"
    kotlin("jvm") version "1.9.24"
    kotlin("plugin.spring") version "1.9.24"
    kotlin("plugin.jpa") version "1.9.24"
}

group = "com.itsm"
version = "0.0.1-SNAPSHOT"

java {
    sourceCompatibility = JavaVersion.VERSION_17
}

repositories {
    mavenCentral()
}

// Test-only override of the Spring Boot BOM: Testcontainers 1.19.8 (Boot 3.3.1's default) cannot talk to
// Docker Engine 29+ (minimum API 1.40), so it reports "Docker unavailable" and integration tests are skipped.
extra["testcontainers.version"] = "1.21.4"

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-amqp")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("io.jsonwebtoken:jjwt-api:0.12.5")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.5")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.5")
    runtimeOnly("org.postgresql:postgresql")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
}

tasks.withType<KotlinCompile> {
    kotlinOptions {
        freeCompilerArgs += "-Xjsr305=strict"
        jvmTarget = "17"
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
    // The application has no default JWT secret (it refuses to start without one); tests get a throwaway one.
    environment("JWT_SECRET", "test-only-jwt-signing-secret-0123456789-abcdefXYZ")
    // Most integration tests create many accounts and log in from the single MockMvc address; the throttles have their own
    // dedicated tests (which set realistic limits) and must not interfere with the rest. Registration is enabled for tests.
    systemProperty("app.registration.enabled", "true")
    // P4's SLA monitor would otherwise run every minute in each test context and write audit rows other tests count;
    // the SLA tests call the monitor directly instead.
    systemProperty("app.sla.monitor.enabled", "false")
    systemProperty("app.escalation.monitor.enabled", "false")
    systemProperty("app.notification.monitor.enabled", "false")
    // P4 reacting to incident events would write SLA/notification/audit rows into other modules' tests; P4's own
    // IncidentEventReactionsIntegrationTest turns it back on.
    systemProperty("app.p4.event-listeners.enabled", "false")
    systemProperty("app.rate-limit.login-pair.max-failures", "100000")
    systemProperty("app.rate-limit.login-ip.max-failures", "100000")
    systemProperty("app.rate-limit.register-ip.max-attempts", "100000")
    // Failures must be diagnosable from the CI log alone: full stack traces, and every non-passing test named.
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showCauses = true
        showStackTraces = true
        showStandardStreams = false // container logs are in the uploaded HTML/XML reports
    }
}
