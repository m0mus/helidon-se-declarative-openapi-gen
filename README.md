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
| Helidon | 4.4.0-M1 (default; configurable) |

---

## Quick start

### 1. Build the generator

```bash
cd openapi-gen
mvn package
```

Produces a self-contained fat jar at
`target/helidon-se-declarative-generator-1.0-SNAPSHOT.jar` (~29 MB).

### 2. Generate a project

```bash
java -jar target/helidon-se-declarative-generator-1.0-SNAPSHOT.jar generate \
  -g helidon-se-declarative \
  -i /path/to/your-spec.yaml \
  -o /path/to/output-dir \
  --additional-properties helidonVersion=4.4.0-M1
```

### 3. Build and run the generated project

```bash
cd /path/to/output-dir
mvn package
java -jar target/*.jar
```

---

## Generator options

Pass any option with `--additional-properties key=value` (repeat for multiple).

| Option | Default | Description |
|--------|---------|-------------|
| `helidonVersion` | `4.4.0-M1` | Helidon version written into the generated `pom.xml` |
| `apiPackage` | `io.helidon.example.api` | Package for endpoint, client, and exception classes |
| `modelPackage` | `io.helidon.example.model` | Package for Jackson POJO model classes |
| `invokerPackage` | `io.helidon.example` | Package for `Main.java` |
| `generateClient` | `true` | Emit a `{Tag}Client.java` REST client stub per tag |
| `generateErrorHandler` | `true` | Emit `{Tag}Exception.java` + `{Tag}ErrorHandler.java` per tag |
| `serveOpenApi` | `true` | Add `helidon-openapi` dependency (serves spec at `/openapi`) |
| `serveBasePath` | *(from spec)* | Base path prefix prepended to all endpoint paths |

Example — override packages and disable client generation:

```bash
java -jar target/helidon-se-declarative-generator-1.0-SNAPSHOT.jar generate \
  -g helidon-se-declarative \
  -i openapi.yaml \
  -o out/ \
  --additional-properties \
    helidonVersion=4.4.0-M1,\
    apiPackage=com.example.api,\
    modelPackage=com.example.model,\
    invokerPackage=com.example,\
    generateClient=false
```

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
| `{Model}.java` | Jackson POJO with `@JsonInclude(NON_NULL)` and `@JsonProperty(required=true)` on required fields |

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
| Per-operation remainder path (e.g. `/{petId}`) | `@Http.Path("/{petId}")` on the method |
| `produces: application/json` | `@Http.Produces(MediaTypes.APPLICATION_JSON_VALUE)` |
| `consumes: application/json` | `@Http.Consumes(MediaTypes.APPLICATION_JSON_VALUE)` |
| Non-200 success status (e.g. `201`) | `@RestServer.Status(201)` |

### Parameters

| OpenAPI `in:` | Generated |
|---------------|-----------|
| `path` | `@Http.PathParam("name") Type name` |
| `query` (required) | `@Http.QueryParam("name") Type name` |
| `query` (optional) | `@Http.QueryParam("name") Optional<Type> name` |
| `header` | `@Http.HeaderParam("Name") Type name` |
| `requestBody` | `@Http.Entity Type body` + `@Http.Consumes` on method |

### Response headers

When a success response declares headers (e.g. `x-next` for pagination), the generated
endpoint method receives an injected `ServerResponse res` parameter and includes a comment
showing the header name to set:

```java
public List<Pet> listPets(ServerResponse res,
                           @Http.QueryParam("limit") Optional<Integer> limit) {
    // Example: res.header(HeaderNames.create("x-next"), "value");
    throw new UnsupportedOperationException("listPets not yet implemented");
}
```

### Security

When an operation has `security` requirements, the scopes are extracted and stored as the
vendor extension `x-security-roles` on the operation — ready to be mapped to
`@RoleValidator.Roles` once you integrate a security provider. The generated
`application.yaml` includes a commented-out security provider stub as a starting point.

### Model names

The schema name `Error` is automatically mapped to `ApiError` to avoid clashing with
`java.lang.Error`.

---

## Petstore example

The `../petstore/` sibling directory contains the hand-written reference implementation
this generator is designed to reproduce. Use it to validate generator output:

```bash
# Generate
java -jar target/helidon-se-declarative-generator-1.0-SNAPSHOT.jar generate \
  -g helidon-se-declarative \
  -i ../petstore/petstore.yaml \
  -o /tmp/petstore-generated \
  --additional-properties helidonVersion=4.4.0-M1

# Build the generated project
cd /tmp/petstore-generated && mvn package

# Run it
java -jar target/*.jar
# → Server started at: http://localhost:8080
# → OpenAPI spec:      http://localhost:8080/openapi

# Smoke test
curl http://localhost:8080/pets
curl http://localhost:8080/openapi
```

---

## Project layout

```
openapi-gen/
├── pom.xml                                         Generator build (fat jar via maven-shade)
└── src/main/
    ├── java/io/helidon/openapi/generator/
    │   └── HelidonSeDeclarativeCodegen.java         Core codegen class (extends AbstractJavaCodegen)
    └── resources/
        ├── META-INF/services/
        │   └── org.openapitools.codegen.CodegenConfig   SPI registration
        └── helidon-se-declarative/                  Mustache templates
            ├── model.mustache                       Jackson POJO
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
