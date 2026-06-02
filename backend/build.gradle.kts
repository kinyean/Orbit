plugins {
	java
	id("org.springframework.boot") version "3.5.0"
	id("io.spring.dependency-management") version "1.1.7"
}

group = "space.orbit"
version = "0.0.1-SNAPSHOT"
description = "Orbit-project RPO simulation backend"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(21)
	}
}

repositories {
	mavenCentral()
}

dependencies {
	implementation("org.springframework.boot:spring-boot-starter-actuator")
	implementation("org.springframework.boot:spring-boot-starter-data-jpa")
	implementation("org.springframework.boot:spring-boot-starter-security")
	implementation("org.springframework.boot:spring-boot-starter-web")
	implementation("org.springframework.boot:spring-boot-starter-websocket")
	implementation("org.flywaydb:flyway-core")
	implementation("org.flywaydb:flyway-database-postgresql")
	implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.6.0")
	// Orekit: flight-dynamics engine (Decision 7). SGP4 in Phase 2; numerical in Phase 3.
	implementation("org.orekit:orekit:13.1.5")
	runtimeOnly("org.postgresql:postgresql")
	testImplementation("org.springframework.boot:spring-boot-starter-test")
	testImplementation("org.springframework.security:spring-security-test")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// --- Orekit data provisioning -------------------------------------------------
// Orekit needs its IERS/EOP/leap-second/ephemeris bundle at runtime. It is NOT on
// Maven Central, and CelesTrak is firewall-blocked from this host — but the
// authoritative GitLab archive IS reachable. This task downloads + unzips it into
// build/orekit-data/orekit-data-main (cached; re-runs only if missing) so tests
// have the data without a manual step. The Docker image fetches its own copy at
// build time (see backend/Dockerfile). Branch is `main` (not `master`).
val orekitDataParent = layout.buildDirectory.dir("orekit-data")
val orekitDataDir = orekitDataParent.map { it.dir("orekit-data-main") }

val provisionOrekitData by tasks.registering(Exec::class) {
	val parent = orekitDataParent.get().asFile
	val marker = orekitDataDir.get().file("tai-utc.dat").asFile
	outputs.dir(orekitDataDir)
	onlyIf { !marker.exists() }
	commandLine(
		"bash", "-c",
		"set -e; mkdir -p '$parent'; " +
		"curl -sSL -o '$parent/orekit-data.zip' " +
		"'https://gitlab.orekit.org/orekit/orekit-data/-/archive/main/orekit-data-main.zip'; " +
		"unzip -q -o '$parent/orekit-data.zip' -d '$parent'; " +
		"rm -f '$parent/orekit-data.zip'"
	)
}

tasks.withType<Test> {
	useJUnitPlatform()
	dependsOn(provisionOrekitData)
	// Point Orekit (in OrekitConfig and the pure-JUnit prop tests) at the provisioned bundle.
	systemProperty("orekit.data.path", orekitDataDir.get().asFile.absolutePath)
}
