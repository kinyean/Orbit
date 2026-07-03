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
	// OAuth2 resource server (Phase 10, US-AUTH-02): validate OIDC bearer JWTs
	// (issuer-uri from env) when orbit.auth.mode=oidc. Inert in stub mode.
	implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
	implementation("org.springframework.boot:spring-boot-starter-web")
	implementation("org.springframework.boot:spring-boot-starter-websocket")
	// Bean Validation (Phase 3A): @Valid on scenario request DTOs; constraints
	// surface in the OpenAPI spec so the generated frontend client is contract-aware.
	implementation("org.springframework.boot:spring-boot-starter-validation")
	implementation("org.flywaydb:flyway-core")
	implementation("org.flywaydb:flyway-database-postgresql")
	// springdoc 2.8.x targets Spring Boot 3.5 (Spring Framework 6.2). 2.6.0 was
	// built against Spring 6.1 and throws NoSuchMethodError on ControllerAdviceBean
	// the moment a @ControllerAdvice exists (it walks advice beans to compute
	// generic responses) — which broke /v3/api-docs once the scenario advice landed.
	implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.8.9")
	// Orekit: flight-dynamics engine (Decision 7). SGP4 in Phase 2; numerical in Phase 3.
	implementation("org.orekit:orekit:13.1.5")
	runtimeOnly("org.postgresql:postgresql")
	testImplementation("org.springframework.boot:spring-boot-starter-test")
	testImplementation("org.springframework.security:spring-security-test")
	// Testcontainers (Phase 3A): @DataJpaTest + full-context tests run against a
	// real, ephemeral Postgres — the schema uses PG-only TEXT[] / jsonb /
	// gen_random_uuid() that H2 can't emulate. Versions managed by the Boot BOM.
	testImplementation("org.springframework.boot:spring-boot-testcontainers")
	testImplementation("org.testcontainers:junit-jupiter")
	testImplementation("org.testcontainers:postgresql")
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
