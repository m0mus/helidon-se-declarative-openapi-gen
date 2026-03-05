# Helidon SE Declarative OpenAPI Generator

An independent [openapi-generator](https://openapi-generator.tech) SPI plugin that generates
**Helidon SE 4.x declarative**-style server code from an OpenAPI 3 specification.

The existing upstream generator (`JavaHelidonServerCodegen`) targets the imperative
`HttpService` routing style. This generator targets the newer annotation-based model
(`@RestServer.Endpoint`, `@Http.GET`, `@Service.Singleton`, …) introduced in Helidon 4.4.x,
which is still marked incubating — making an independent generator the right starting point
until the API stabilises.

---

## Prerequisites

| Tool | Version |
|------|---------|
| Java | 21+ |
| Maven | 3.9+ |
| Helidon | 4.4.0-M2 (default; configurable) |

---

## Quick start

### 1. Build and install the generator

```bash
cd openapi-gen
mvn install -DskipTests
```

This installs `helidon-se-declarative-generator-1.0-SNAPSHOT.jar` into your local Maven
repository, where the openapi-generator Maven plugin can find it as a plugin dependency.

### 2. Add the plugin to your project's `pom.xml`

```xml
<plugin>
    <groupId>org.openapitools</groupId>
    <artifactId>openapi-generator-maven-plugin</artifactId>
    <version>7.11.0</version>
    <executions>
        <execution>
            <goals>
                <goal>generate</goal>
            </goals>
            <configuration>
                <generatorName>helidon-se-declarative</generatorName>
                <inputSpec>${project.basedir}/src/main/resources/openapi.yaml</inputSpec>
                <output>${project.build.directory}/generated-sources/openapi</output>
                <configOptions>
                    <helidonVersion>4.4.0-M2</helidonVersion>
                    <apiPackage>com.example.api</apiPackage>
                    <modelPackage>com.example.model</modelPackage>
                    <invokerPackage>com.example</invokerPackage>
                </configOptions>
            </configuration>
        </execution>
    </executions>
    <dependencies>
        <dependency>
            <groupId>io.helidon.openapi</groupId>
            <artifactId>helidon-se-declarative-generator</artifactId>
            <version>1.0-SNAPSHOT</version>
        </dependency>
    </dependencies>
</plugin>
```

### 3. Generate

```bash
mvn generate-sources
```

Source files are written to `target/generated-sources/openapi/`.

### 4. Build and run the generated project

```bash
mvn package
java -jar target/*.jar
```

---

## Generator options

Set options under `<configOptions>` in the plugin configuration.

| Option | Default | Description |
|--------|---------|-------------|
| `helidonVersion` | `4.4.0-M2` | Helidon version written into the generated `pom.xml` |
| `apiPackage` | `io.helidon.example.api` | Package for endpoint, client, and exception classes |
| `modelPackage` | `io.helidon.example.model` | Package for model POJO classes |
| `invokerPackage` | `io.helidon.example` | Package for `Main.java` |
| `generateClient` | `true` | Emit a `{Tag}Client.java` REST client stub per tag |
| `generateErrorHandler` | `true` | Emit `{Tag}Exception.java` + `{Tag}ErrorHandler.java` per tag |
| `serveOpenApi` | `true` | Add `helidon-openapi` dependency (serves spec at `/openapi`) |
| `serveBasePath` | *(from spec)* | Base path prefix prepended to all endpoint paths |
| `corsEnabled` | `false` | Add `@Cors.Defaults` to every endpoint class and `helidon-webserver-cors` dependency |
| `ftEnabled` | `false` | Add `@Ft.Retry` to every REST client interface and `helidon-fault-tolerance` dependency |
| `tracingEnabled` | `false` | Add `@Tracing.Traced` to every endpoint class and `helidon-tracing` dependency |
| `metricsEnabled` | `false` | Add `@Metrics.Timed` to every endpoint method and `helidon-metrics-api` dependency |

---

## What gets generated

For each OpenAPI **tag group** (one endpoint class per tag):

| File | Description |
|------|-------------|
| `{Tag}Api.java` | `@Http.Path` interface — shared HTTP contract |
| `{Tag}Endpoint.java` | `@RestServer.Endpoint @Service.Singleton` — server implementation stub |
| `{Tag}Client.java` | `@RestClient.Endpoint` stub — declarative REST client *(if `generateClient=true`)* |
| `{Tag}Exception.java` | `RuntimeException` carrying an HTTP `Status` *(if `generateErrorHandler=true`)* |
| `{Tag}ErrorHandler.java` | `ErrorHandler<{Tag}Exception>` — serialises errors as JSON *(if `generateErrorHandler=true`)* |

For each OpenAPI **schema** (excluding array aliases):

| File | Description |
|------|-------------|
| `{Model}.java` | Helidon build-time JSON binding POJO with `@Json.Entity`; `@Json.Required` on required fields; inner enum types; field initialisers for defaults |

Supporting files (one per project):

| File | Description |
|------|-------------|
| `pom.xml` | Maven project with Helidon BOM, annotation processor, service plugin |
| `Main.java` | `@Service.GenerateBinding` entry point |
| `src/main/resources/application.yaml` | Server port + security stub (commented out) |
| `src/main/resources/logging.properties` | JUL logging configuration |

---

## OpenAPI → Helidon SE mapping

### Operations

| OpenAPI | Generated |
|---------|-----------|
| `get` / `post` / `put` / `delete` / `patch` | `@Http.GET` / `@Http.POST` / `@Http.PUT` / `@Http.DELETE` / `@Http.PATCH` |
| Common path prefix of all operations in a tag | `@Http.Path("…")` on the endpoint class |
| Per-operation remainder path (e.g. `/{id}`) | `@Http.Path("/{id}")` on the method |
| `produces: application/json` | `@Http.Produces(MediaTypes.APPLICATION_JSON_VALUE)` |
| `consumes: application/json` | `@Http.Consumes(MediaTypes.APPLICATION_JSON_VALUE)` |
| `consumes: application/x-www-form-urlencoded` | `@Http.Consumes(MediaTypes.APPLICATION_FORM_URLENCODED_VALUE)` + `@Http.Entity Parameters formBody` |
| `consumes: multipart/form-data` | `@Http.Consumes(MediaTypes.MULTIPART_FORM_DATA_VALUE)` + `@Http.Entity ReadableEntity formBody` |
| Non-200 success status (e.g. `201`) | `@RestServer.Status(201)` |
| `deprecated: true` | `@Deprecated` on the method |
| `summary` / `description` | Javadoc comment on the method |

### Parameters

| OpenAPI `in:` | Generated |
|---------------|-----------|
| `path` | `@Http.PathParam("name") Type name` |
| `query` (required) | `@Http.QueryParam("name") Type name` |
| `query` (optional) | `@Http.QueryParam("name") Optional<Type> name` |
| `header` | `@Http.HeaderParam("Name") Type name` |
| `requestBody` (JSON) | `@Http.Entity Type body` + `@Http.Consumes` on method |

### Parameter validation

OpenAPI schema constraints on parameters are mapped to Helidon `@Validation.*` annotations:

| Constraint | Generated annotation |
|------------|---------------------|
| `minLength` / `maxLength` | `@Validation.String.Length(min = N, value = M)` |
| `pattern` | `@Validation.String.Pattern("…")` |
| `minimum` / `maximum` (integer) | `@Validation.Integer.Min(N)` / `@Validation.Integer.Max(N)` |
| `minimum` / `maximum` (long) | `@Validation.Long.Min(NL)` / `@Validation.Long.Max(NL)` |
| `minimum` / `maximum` (number) | `@Validation.Number.Min("N")` / `@Validation.Number.Max("N")` |
| `minItems` / `maxItems` (array) | `@Validation.Collection.Size(min = N, value = M)` |

The same mappings apply to model property constraints.

### Response headers

When a 2xx response declares headers, the generated endpoint method gets declarative
header annotations rather than a `ServerResponse` parameter:

- **Static value** (header schema has a `default`): `@RestServer.Header(name = "…", value = "…")`
- **Dynamic value**: `@RestServer.ComputedHeader(name = "…", function = "…HeaderFn")`

### Security

Operations with `security` requirements emit `@RoleValidator.Roles` on the method and
receive an injected `SecurityContext securityContext` parameter. The generated
`application.yaml` includes a commented-out security provider stub as a starting point.

### Models

| OpenAPI schema feature | Generated Java |
|------------------------|----------------|
| `required` field | `@Json.Required` annotation |
| `enum` values | Inner `public enum {Prop}Enum` class with constants |
| `default` value | Field initialiser (e.g. `= StatusEnum.ACTIVE`, `= 0`, `= 1.0`) |
| `nullable: true` | No special annotation needed (Helidon JSON binding omits nulls by default) |
| `description` on property | Javadoc comment on the field |
| `deprecated: true` on schema | `@Deprecated` on the class |
| `description` on schema | Javadoc comment on the class |

### Model names

The schema name `Error` is automatically mapped to `ApiError` to avoid clashing with
`java.lang.Error`.

---

## Project layout

```
openapi-gen/
├── pom.xml                                         Generator build
└── src/main/
    ├── java/io/helidon/openapi/generator/
    │   └── HelidonSeDeclarativeCodegen.java         Core codegen class (extends AbstractJavaCodegen)
    └── resources/
        ├── META-INF/services/
        │   └── org.openapitools.codegen.CodegenConfig   SPI registration
        └── helidon-se-declarative/                  Mustache templates
            ├── model.mustache                       Helidon JSON binding POJO
            ├── api-interface.mustache               @Http.Path interface ({Tag}Api)
            ├── api.mustache                         @RestServer.Endpoint class ({Tag}Endpoint)
            ├── restClient.mustache                  @RestClient.Endpoint stub ({Tag}Client)
            ├── apiException.mustache                RuntimeException subclass ({Tag}Exception)
            ├── errorHandler.mustache                ErrorHandler ({Tag}ErrorHandler)
            ├── Main.java.mustache                   @Service.GenerateBinding entry point
            ├── pom.xml.mustache                     Maven project descriptor
            ├── application.yaml.mustache            Server + security configuration
            └── logging.properties.mustache          JUL logging
```

---

## Extending the generator

The generator is a standard Java class that extends `AbstractJavaCodegen`. Common
customisation points:

- **Add a new template variable** — put a key into the `OperationsMap` inside
  `postProcessOperationsWithModels()`, then reference it as `{{myKey}}` in the template.
- **Change generated class names** — override `toApiName()` and `apiFilename()`.
- **Add a new per-tag file** — add an entry to `apiTemplateFiles` in `processOpts()` and
  create the corresponding `.mustache` file in `src/main/resources/helidon-se-declarative/`.
- **Add a new global file** — add a `SupportingFile` entry in `processOpts()`.
- **Handle a new OpenAPI feature** — enrich operations in `fromOperation()` via
  `op.vendorExtensions.put(...)` and use `{{vendorExtensions.x-my-flag}}` in templates.

---

## Contributing upstream

Once the Helidon SE declarative API is stable (expected post-4.4.x GA), this generator
can be contributed to the upstream openapi-generator project as a replacement for
`JavaHelidonServerCodegen`'s declarative target. The independent structure was chosen
deliberately to allow free iteration without upstream review gates during the incubating
phase.

---

## License

Apache License 2.0 — same as Helidon and openapi-generator.
