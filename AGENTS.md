# AGENTS.md

This file provides guidance to AI coding agents when working with code in this repository.

## Overview

Reference FHIR R4 server for the [Da Vinci US Drug Formulary Implementation Guide](https://hl7.org/fhir/us/davinci-drug-formulary/) ([CI build](https://build.fhir.org/ig/HL7/davinci-pdex-formulary/)). It is a fork of the HAPI FHIR JPA Server starter (`hapi-fhir-jpaserver-starter`, HAPI 7.6.0, Spring Boot, Java 17, WAR packaging) with formulary-specific customizations layered on top. The hosted instance (https://drug-formulary-ri.davinci.hl7.org/fhir) is read only; data loading happens on the `formulary-write` branch.

## Commands

```bash
# Run locally (FHIR endpoint at http://localhost:8080/fhir)
mvn spring-boot:run
# or with Jetty instead of Tomcat
mvn -Pjetty spring-boot:run

# Build
mvn clean install -DskipTests

# Run all tests / a single test class / a single test method
mvn test
mvn test -Dtest=CustomOperationTest
mvn test -Dtest=CustomOperationTest#methodName

# Docker (test data pre-loaded)
./build-docker-image.sh
docker compose up
```

Before running locally, set `hapi.fhir.server_address` in `src/main/resources/application.yaml` to `http://localhost:8080/fhir` (the hosted URL is the commented-out alternative on the adjacent line).

Deployment is automatic: any change merged to `master` deploys to the hosted server.

## Architecture

The source tree is split into two top-level packages by origin:

- `src/main/java/ca/uhn/fhir/jpa/starter/` - Code inherited from the upstream HAPI starter project. Keep this package identical to the upstream release so that version bumps merge cleanly. The only intentional deviation is the `scanBasePackages` attribute in `Application.java`, which adds `org.hl7.davinci` to component scanning.
- `src/main/java/org/hl7/davinci/` - All formulary-specific custom code:
  - `FormularyConfig` - Spring configuration that wires everything below into the starter: registers the four FHIR interceptors and the `InsurancePlanExportProvider` on the `RestfulServer` and defines the servlet registrations for the `/oauth/*`, `/.well-known/*`, and `/debug/*` endpoints.
  - `FormularyProperties` - custom configuration values (`admin_token`), bound to the same `hapi.fhir` prefix as the starter's `AppProperties`.
  - `authorization/` - Self-contained SMART-on-FHIR OAuth server (authorization, token, introspection, and client registration endpoints under `/oauth/*`). Uses its own H2 database (`Database.java`, file `oauth`) separate from the main FHIR JPA database. `AuthUtils.java` holds token/scope logic; test users (e.g. PDexPatient/password) are seeded here.
  - `interceptors/` - The behavioral core of the reference implementation:
    - `PatientAuthorizationInterceptor` - enforces Bearer-token auth on the protected resources (`Patient`, `Coverage`); everything else is publicly readable. Honors an `ADMIN_TOKEN` env var (see `docker-compose.yml`) that bypasses the OAuth flow.
    - `ReadOnlyInterceptor` - rejects mutating FHIR operations, making the server read only.
    - `MetadataProvider` - customizes the CapabilityStatement to advertise the OAuth endpoints.
    - `ExportInterceptor` - supports `/InsurancePlan/$export` bulk data export.
  - `resourceproviders/` - `InsurancePlanExportProvider` implementing `/InsurancePlan/$export` bulk data export.
  - `datainitializer/` - loads FHIR resources from the directories listed in the top-level `initial-data` configuration property at startup, when that list is non-empty.
  - `wellknown/` - `/.well-known/smart-configuration` endpoint.
  - `debug/` - `/debug/Clients`, `/debug/Users`, `/debug/UpdateClient` helper endpoints for OAuth troubleshooting.
  - `ServerLogger.java` - backs the `/Log` endpoint.

Configuration is driven by `src/main/resources/application.yaml` (bound to `AppProperties`). The main FHIR data store is file-based H2 at `./target/database/h2` with Lucene indexes; pre-loaded formulary data ships in `data/` and is copied in by the Docker build.

Tests in `src/test/java` are almost entirely upstream starter tests (generic HAPI IT suites), not formulary-specific.

## Implementation Guide Resource Mapping

The IG defines its profiles on the following base resources:

- FormularyItem is profiled on `Basic` ([usdf-FormularyItem](https://hl7.org/fhir/us/davinci-drug-formulary/StructureDefinition-usdf-FormularyItem.html)).
- FormularyDrug is profiled on `MedicationKnowledge` ([usdf-FormularyDrug](https://hl7.org/fhir/us/davinci-drug-formulary/StructureDefinition-usdf-FormularyDrug.html)).
- PayerInsurancePlan and Formulary are profiled on `InsurancePlan` ([usdf-PayerInsurancePlan](https://hl7.org/fhir/us/davinci-drug-formulary/StructureDefinition-usdf-PayerInsurancePlan.html), [usdf-Formulary](https://hl7.org/fhir/us/davinci-drug-formulary/StructureDefinition-usdf-Formulary.html)).

See the IG's [anticipated queries page](https://hl7.org/fhir/us/davinci-drug-formulary/queries.html) for the search patterns this server is expected to support.
