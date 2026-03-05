# Architecture & Design

## Table of Contents

1. [Goals and scope](#1-goals-and-scope)
2. [Fit within openapi-generator](#2-fit-within-openapi-generator)
3. [Repository structure](#3-repository-structure)
4. [Data flow: spec to source files](#4-data-flow-spec-to-source-files)
5. [Generator class design](#5-generator-class-design)
6. [Template system](#6-template-system)
7. [Key design decisions](#7-key-design-decisions)
8. [Non-obvious implementation details](#8-non-obvious-implementation-details)
9. [Generated project structure](#9-generated-project-structure)
10. [Testing strategy](#10-testing-strategy)
11. [Extension points](#11-extension-points)
12. [Future work](#12-future-work)

---

## 1. Goals and scope

The generator produces **Helidon SE 4.x declarative-style** server code from any OpenAPI 3
specification. The target style uses annotation-based endpoint registration
(`@RestServer.Endpoint`, `@Http.GET`, `@Service.Singleton`, …) that was introduced in
Helidon 4.4.x as an incubating feature.

The upstream openapi-generator project already ships `JavaHelidonServerCodegen`, but it
targets the older **imperative** `HttpService` routing style. Contributing a declarative
target upstream during the incubating phase would be premature — the API may still change,
and upstream has strict conventions and long review cycles. This generator is therefore
structured as an **independent SPI plugin** that can be contributed upstream once the
declarative API stabilises.

---

## 2. Fit within openapi-generator

openapi-generator provides a standard SPI mechanism for adding generators:

```
openapi-generator (library)
└── CodegenConfig interface          ← implemented by this project
    └── AbstractJavaCodegen          ← extended by HelidonSeDeclarativeCodegen
        └── HelidonSeDeclarativeCodegen
```

Registration is via the standard Java ServiceLoader file:

```
META-INF/services/org.openapitools.codegen.CodegenConfig
  → io.helidon.openapi.generator.HelidonSeDeclarativeCodegen
```

The generator is published as a **thin jar** and consumed as a dependency of the standard
`openapi-generator-maven-plugin`. Users add the plugin to their project's `pom.xml` and
list the generator artifact as a plugin dependency:

```xml
<plugin>
    <groupId>org.openapitools</groupId>
    <artifactId>openapi-generator-maven-plugin</artifactId>
    <version>7.11.0</version>
    <dependencies>
        <dependency>
            <groupId>io.helidon.openapi</groupId>
            <artifactId>helidon-se-declarative-generator</artifactId>
            <version>1.0-SNAPSHOT</version>
        </dependency>
    </dependencies>
    ...
</plugin>
```

Maven resolves all transitive dependencies automatically. No special packaging of the
generator itself is required.

---

## 3. Repository structure

```
openapi-gen/
├── pom.xml                                   Generator build
├── README.md                                 User-facing quick-start
├── ARCHITECTURE.md                           This document
└── src/
    ├── main/
    │   ├── java/io/helidon/openapi/generator/
    │   │   └── HelidonSeDeclarativeCodegen.java   Core codegen class
    │   └── resources/
    │       ├── META-INF/services/
    │       │   └── org.openapitools.codegen.CodegenConfig   SPI registration
    │       └── helidon-se-declarative/        Mustache templates (10 files)
    └── test/
        ├── java/io/helidon/openapi/generator/
        │   ├── HelidonSeDeclarativeCodegenTest.java   Unit tests (22)
        │   ├── PetstoreGenerationIT.java              Petstore spec integration tests (35)
        │   ├── FeaturesGenerationIT.java              Gap-feature integration tests (16)
        │   ├── FormGenerationIT.java                  Form/multipart tests (7)
        │   ├── ValidationGenerationIT.java            Validation annotation tests (9)
        │   ├── SecurityGenerationIT.java              Security role tests (10)
        │   ├── CorsGenerationIT.java                  CORS option tests (3)
        │   ├── FtGenerationIT.java                    Fault-tolerance option tests (3)
        │   └── ObservabilityGenerationIT.java         Tracing + metrics tests (6)
        └── resources/
            ├── petstore.yaml                  Petstore spec (multi-tag, path params, headers)
            ├── features.yaml                  Spec exercising deprecated, enums, defaults, validation
            └── form.yaml                      Spec with form-urlencoded and multipart operations
```

---

## 4. Data flow: spec to source files

```
openapi.yaml
     │
     ▼
Swagger Parser (bundled in openapi-generator)
     │  parses into io.swagger.v3.oas.models.OpenAPI
     ▼
HelidonSeDeclarativeCodegen.preprocessOpenAPI()
     │  extracts server base path → additionalProperties["serverBasePath"]
     ▼
HelidonSeDeclarativeCodegen.fromModel(name, schema)
     │  converts each schema → CodegenModel
     │  removes stale annotation import names:
     │    "ApiModel", "ApiModelProperty", "Schema" (swagger 1.x / openapi 3 annotation shorthands)
     │    "JsonInclude", "JsonProperty", "JsonNullable" (Jackson / openapi-nullable)
     ▼
HelidonSeDeclarativeCodegen.fromOperation(path, method, operation, servers)
     │  converts each operation → CodegenOperation
     │  enriches with vendorExtensions:
     │    x-http-annotation            "@Http.GET" / "@Http.POST" / …
     │    x-consumes-value             MediaTypes constant name for @Http.Consumes
     │    x-is-form-urlencoded         true for application/x-www-form-urlencoded
     │    x-is-multipart               true for multipart/form-data
     │    x-form-param-names           comma-joined form field names (for TODO comments)
     │    x-status-code                non-200 success code integer (e.g. 201)
     │    x-has-static-headers         true when a 2xx response has static-value headers
     │    x-static-headers             [{name, value}, …]
     │    x-has-computed-headers       true when a 2xx response has dynamic headers
     │    x-computed-headers           [{name, functionName}, …]
     │    x-has-response-headers       true if either static or computed headers present
     │    x-security-roles             [scope, …]
     │    x-has-security-roles         true if roles list is non-empty
     │    x-roles-annotation-value     pre-formatted Java annotation value string
     │    x-needs-leading-comma-for-security  true when other params precede SecurityContext
     │  wraps optional query params in Optional<T> (stores original type as x-bare-type)
     │  collapses form params into a single typed body param (Parameters or ReadableEntity)
     ▼
HelidonSeDeclarativeCodegen.postProcessAllModels(objs)
     │  for each model property:
     │    marks required with x-json-required vendorExtension
     │    builds @Validation.* annotations → x-validation-annotations
     │    formats default value as Java literal → x-default-value
     │  marks model with x-has-validations if any property has constraints
     │  injects Validation import into model's import list
     ▼
HelidonSeDeclarativeCodegen.postProcessOperationsWithModels(objs, allModels)
     │  groups operations by tag → one OperationsMap per tag
     │  computes helidonBasePath (common path prefix without path-param segments)
     │  per-operation:
     │    x-method-path, x-has-method-path  (sub-path after common prefix)
     │    x-return-type, x-is-void          (null-safe return type)
     │    x-validation-annotations          (per-param validation from buildParamValidationAnnotations)
     │    x-needs-leading-comma-for-security
     │  accumulates tag-level boolean flags:
     │    hasComputedHeaders, hasOptionalQueryParams, hasSecurityRoles,
     │    hasParamValidation, hasFormOperations, hasMultipartOperations
     │  finds errorModel (first non-2xx response data type)
     ▼
DefaultGenerator (openapi-generator)
     │  merges OperationsMap + additionalProperties → Mustache context
     │  renders each template with the merged context
     ▼
Generated Java source files + supporting files
```

---

## 5. Generator class design

`HelidonSeDeclarativeCodegen` extends `AbstractJavaCodegen`. The override points are:

### Constructor

Sets up all defaults that don't depend on user-supplied options:

- `templateDir` / `embeddedTemplateDir` → `"helidon-se-declarative"` (classpath prefix)
- Default package names (`apiPackage`, `modelPackage`, `invokerPackage`)
- `modelNameMapping.put("Error", "ApiError")` — avoids clash with `java.lang.Error`
- Clears all doc/test template maps inherited from the parent (not needed here)
- Registers `apiTemplateFiles` (`api.mustache`, `api-interface.mustache`) and
  `modelTemplateFiles` (`model.mustache`)
- Adds supporting files: `pom.xml`, `application.yaml`, `logging.properties`
- Declares generator options via `addOption()`

### `processOpts()`

Called after the user's `--additional-properties` are applied. Reads each option out of
`additionalProperties`, stores it in an instance field, then writes it back so templates
can access it. Conditionally registers optional template files:

- `generateClient=true` → adds `restClient.mustache` → `{Tag}Client.java`
- `generateErrorHandler=true` → adds `apiException.mustache` + `errorHandler.mustache`
- Always adds `Main.java.mustache` as a supporting file (path depends on the resolved
  `invokerPackage`)

Boolean feature flags (`corsEnabled`, `ftEnabled`, `tracingEnabled`, `metricsEnabled`) are
stored in `additionalProperties` so templates can use them as global conditionals.

### `toApiName(String name)`

Returns the class name prefix used for all per-tag files. The override returns plain
camelCase (`"Pets"`) rather than the default which appends `"Api"` (`"PetsApi"`). This
keeps the naming readable: `PetsEndpoint`, `PetsApi`, `PetsClient`, `PetsException`.
Empty or null input → `"Default"`.

### `apiFilename(String templateName, String tag)`

Maps each template name to its output filename. A switch expression handles the five
possible templates; anything else delegates to the parent.

### `preprocessOpenAPI(OpenAPI openAPI)`

Runs before any per-operation processing. Extracts the path component from
`servers[0].url` (e.g. `/v1` from `https://api.example.com/v1`) and stores it as
`additionalProperties["serverBasePath"]`.

### `fromModel(String name, Schema schema)`

Calls the parent and then removes stale import shorthand names from `model.imports`:

| Removed name | Would resolve to |
|---|---|
| `"ApiModel"`, `"ApiModelProperty"` | `io.swagger.annotations.*` (Swagger 1.x — not on Helidon classpath) |
| `"Schema"` | `io.swagger.v3.annotations.media.Schema` (OpenAPI 3 annotation — not needed) |
| `"JsonInclude"`, `"JsonProperty"` | `com.fasterxml.jackson.annotation.*` (replaced by Helidon JSON binding) |
| `"JsonNullable"` | `org.openapitools.jackson.nullable.JsonNullable` (openapi-nullable wrapper — unused) |

### `fromOperation(String path, String httpMethod, Operation operation, List<Server> servers)`

Calls the parent and then enriches `op.vendorExtensions`. Key logic:

1. **HTTP method annotation** — `@Http.GET`, `@Http.POST`, etc. from `httpMethod.toUpperCase()`.

2. **Consumes media type** — inspects `op.consumes[0].mediaType` and sets `x-consumes-value`
   to the appropriate `MediaTypes.*` constant name. Defaults to `APPLICATION_JSON_VALUE`.
   Detects `multipart/form-data` and `application/x-www-form-urlencoded` and sets
   `x-is-multipart` / `x-is-form-urlencoded` flags.

3. **Form param collapse** — when `op.formParams` is non-empty (form-encoded or multipart
   request body), the individual per-field params are replaced by a single synthetic
   `@Http.Entity` body parameter. Type is `Parameters` (form-urlencoded) or
   `ReadableEntity` (multipart). Helidon SE declarative has no `@Http.FormParam`.

4. **Optional query params** — non-required query parameters are wrapped in `Optional<T>`;
   the original type is stored as `x-bare-type`.

5. **Success status override** — iterates `operation.responses` to find the first non-200
   2xx code and stores it as `x-status-code`.

6. **Response headers** — for 2xx responses that declare headers, distinguishes static
   (schema has `default`) from dynamic headers. Static headers become `@RestServer.Header`;
   dynamic headers become `@RestServer.ComputedHeader` with a camelCase function name
   derived from the header name (via `headerNameToFunctionName()`).

7. **Security roles** — collects scopes from `operation.security` requirements, stores them
   as `x-security-roles`, and pre-formats the Java annotation value string
   (`"role"` or `{"r1", "r2"}`).

### `postProcessAllModels(Map<String, ModelsMap> objs)`

Iterates all model properties and for each:

- **`x-json-required`** — set `true` on `required: true` properties for `@Json.Required`.
- **`x-validation-annotations`** — calls `buildValidationAnnotations(prop)` to map
  OpenAPI constraints (`minLength`, `maxLength`, `pattern`, `minimum`, `maximum`,
  `minItems`, `maxItems`) to a list of `@Validation.*` annotation strings.
- **`x-default-value`** — calls `formatDefaultValue(prop)` to produce a Java literal
  field initialiser string. Handles strings (quoted), longs (`L` suffix), floats (`f`
  suffix), enums (upstream already formats as `TypeName.CONSTANT`), and primitives
  (value as-is). Returns `null` for arrays, maps, and properties with no default.

When any property has validation constraints, `x-has-validations` is set on the model and
a `Validation` import is injected into the model's import list.

### `postProcessOperationsWithModels(OperationsMap objs, List<ModelMap> allModels)`

Called once per tag group. Key responsibilities:

1. **`helidonBasePath`** — the longest common path prefix shared by all operations in the
   tag, computed by `computeCommonPath()`. Deliberately named differently from `"basePath"`
   to avoid the context collision (see [section 8](#8-non-obvious-implementation-details)).

2. **Per-operation sub-path** — strips the common prefix from each operation's full path
   and stores it as `x-method-path`. Empty sub-paths are omitted.

3. **Parameter validation** — calls `buildParamValidationAnnotations(param)` for every
   parameter; stores the result as `x-validation-annotations` on the parameter and
   accumulates `anyParamValidation`.

4. **Accumulates boolean flags** across all operations:
   `hasComputedHeaders`, `hasOptionalQueryParams`, `hasSecurityRoles`,
   `hasParamValidation`, `hasFormOperations`, `hasMultipartOperations`.

5. **`errorModel`** — the data type of the first non-2xx response, used by
   `errorHandler.mustache`.

6. **`classname` / `classnameLowercase`** — exposed for templates that need them outside
   the `{{#operations}}` block.

### Private helpers

| Method | Purpose |
|--------|---------|
| `computeCommonPath(ops)` | Longest static path prefix shared by all operations in a tag; stops before any `{param}` segment |
| `headerNameToFunctionName(name)` | Converts `"x-next"` → `"xNextHeaderFn"` for `@RestServer.ComputedHeader` |
| `buildValidationAnnotations(prop)` | Maps `CodegenProperty` constraints to `@Validation.*` annotation strings |
| `buildParamValidationAnnotations(param)` | Same logic for `CodegenParameter` (mirrors the property version) |
| `formatDefaultValue(prop)` | Formats a property's default as a Java literal (quoted string, `L`/`f` suffix, enum reference, primitive) |

---

## 6. Template system

All templates live in `src/main/resources/helidon-se-declarative/` and are rendered by
openapi-generator's built-in Mustache engine.

### Template inventory

| Template | Output | Scope |
|----------|--------|-------|
| `model.mustache` | `{Model}.java` | Per schema |
| `api-interface.mustache` | `{Tag}Api.java` | Per tag |
| `api.mustache` | `{Tag}Endpoint.java` | Per tag |
| `restClient.mustache` | `{Tag}Client.java` | Per tag (if `generateClient`) |
| `apiException.mustache` | `{Tag}Exception.java` | Per tag (if `generateErrorHandler`) |
| `errorHandler.mustache` | `{Tag}ErrorHandler.java` | Per tag (if `generateErrorHandler`) |
| `Main.java.mustache` | `Main.java` | Once (supporting file) |
| `pom.xml.mustache` | `pom.xml` | Once (supporting file) |
| `application.yaml.mustache` | `application.yaml` | Once (supporting file) |
| `logging.properties.mustache` | `logging.properties` | Once (supporting file) |

### Template variable sources

Variables flow into templates from two sources that are merged by `DefaultGenerator`:

- **`additionalProperties`** — global, always present; set in constructor and
  `processOpts()`. Available in every template without a surrounding block. Includes
  `corsEnabled`, `ftEnabled`, `tracingEnabled`, `metricsEnabled`, `serveOpenApi`, etc.
- **`OperationsMap`** — per-tag; wraps `List<CodegenOperation>` under the `operations`
  key and is also used as a flat map for tag-level variables like `helidonBasePath`,
  `hasParamValidation`, `hasFormOperations`, `errorModel`, and `classname`.

### Shared interface pattern

`{Tag}Api` is the central abstraction. It declares all HTTP annotations and is implemented
by both the server endpoint and (structurally) the REST client:

```
{Tag}Api  (interface — @Http.Path, @Http.GET, @Http.Path per method, ...)
    └── {Tag}Endpoint   implements {Tag}Api   (@RestServer.Endpoint)
    └── {Tag}Client     extends {Tag}Api      (@RestClient.Endpoint)
```

This means the HTTP contract is defined once and shared by client and server — a change
to the interface propagates to both automatically.

---

## 7. Key design decisions

### Independent SPI plugin vs. upstream contribution

Chosen: **independent plugin**.

Rationale: the Helidon SE declarative API is marked "incubating" in 4.4.x. Contributing
to upstream during this phase would lock the generator into the upstream release cadence
(months between releases) and require strict adherence to upstream conventions
(mandatory unit test coverage thresholds, specific template structure). As an independent
plugin, this project can iterate freely and be contributed upstream once the API reaches
GA stability.

### Helidon build-time JSON binding over Jackson

Generated model POJOs use `@Json.Entity` (Helidon build-time JSON binding) rather than
Jackson annotations. This matches the reflection-free, annotation-processor-driven
philosophy of the Helidon SE declarative stack. Jackson would add a runtime dependency
that is not needed; Helidon JSON binding generates efficient serialisers at compile time.

### Form params collapsed into a single body parameter

Helidon SE declarative has no `@Http.FormParam` equivalent. Rather than exposing
individual form fields as separate parameters (which would require an imperative
`ServerRequest` injection), the generator collapses all form fields into one typed body
parameter: `@Http.Entity Parameters body` (form-urlencoded) or
`@Http.Entity ReadableEntity body` (multipart). This keeps the method signature
declarative while making it obvious where the form data comes from.

### Thin jar distribution via the Maven plugin

The generator is published as a plain jar with no special packaging. Users consume it as a
`<dependency>` inside the `openapi-generator-maven-plugin` block in their own `pom.xml`.
This follows the same pattern used by every other custom openapi-generator plugin.

### No Helidon runtime dependency in the generator

`openapi-gen/pom.xml` depends only on openapi-generator. Helidon itself is a **generated
output** dependency, not an input to the generator. This keeps the generator's classpath
small and avoids any Helidon-version lock-in at the generator level.

### One endpoint class per OpenAPI tag

Operations are grouped by their first tag. Un-tagged operations go into `Default*`. This
mirrors how Helidon endpoints are typically structured: one class per resource.

### `generateClient` and `generateErrorHandler` as opt-out options

Both default to `true` because most server projects benefit from having a typed client
stub and a consistent error-handling pattern. They are opt-out rather than opt-in so that
the default run produces a complete, working project out of the box.

---

## 8. Non-obvious implementation details

### The `basePath` collision

`AbstractJavaCodegen.preprocessOpenAPI()` stores the full server URL (e.g.
`https://api.example.com/v1`) under `additionalProperties["basePath"]`. When
`DefaultGenerator` builds the Mustache context for each tag, it merges
`additionalProperties` into the `OperationsMap`. This means any key put into
`OperationsMap` with the name `"basePath"` is silently overwritten by the full URL
before the template renders.

Solution: use `"helidonBasePath"` as the key in `postProcessOperationsWithModels()`. This
name does not appear in `additionalProperties` and is never overwritten.

### Triple-mustache for generic types

Mustache's double-brace syntax (`{{returnType}}`) HTML-escapes the value, turning
`List<Pet>` into `List&lt;Pet&gt;`. Triple-braces (`{{{returnType}}}`) disable escaping.
All references to Java type expressions use triple-braces: `{{{returnType}}}`,
`{{{dataType}}}`, `{{{datatypeWithEnum}}}`, `{{{annotation}}}`.

### `{{#vars}}` scope inside `{{#model}}`

The openapi-generator Mustache context nests as:

```
{{#models}}        ← List of ModelMap (one per model file)
  {{#model}}       ← CodegenModel — has vars, but also has imports as Set<String>
    {{#vars}}      ← iterates CodegenModel.vars (List<CodegenProperty>)
    {{/vars}}
  {{/model}}
  {{#imports}}     ← iterates ModelMap.imports (List<Map<String,String>>)
  {{/imports}}
{{/models}}
```

`{{#imports}}` must be inside `{{#models}}` but **outside** `{{#model}}` to pick up the
`ModelMap.imports` list (which has the resolved `import` key per entry). Placing it
inside `{{#model}}` would iterate the raw `Set<String>` from `CodegenModel`, which has
no `import` key and renders nothing.

### Stale annotation imports

`AbstractJavaCodegen` unconditionally adds shorthand names such as `"ApiModel"`,
`"JsonInclude"`, and `"JsonNullable"` to `CodegenModel.imports`. These resolve via an
internal mapping to third-party annotation classes that are not on the Helidon classpath.
Fix: override `fromModel()` and call `model.imports.remove(...)` for each stale name
before returning. This must happen in `fromModel()` (before the short names are resolved
to full import paths by the framework).

### `fromOperation` signature change in 7.x

openapi-generator 7.x changed the `fromOperation` override signature. The parameters
`Map<String, Schema> allDefinitions` and `OpenAPI openAPI` were removed; the full OpenAPI
model is now accessible as `this.openAPI` (a protected field on the parent). The correct
4-argument signature is:

```java
public CodegenOperation fromOperation(String path, String httpMethod,
                                      Operation operation, List<Server> servers)
```

### `hasFormParams` / `hasBodyParam` are computed, not fields

`CodegenOperation.hasFormParams` and `hasBodyParam` are not direct Java fields — they are
derived by the Mustache framework from `formParams.isEmpty()` and `bodyParam != null`.
To clear form params in code, call `op.formParams.clear()` (not `op.hasFormParams = false`).
To signal a body param is present, assign `op.bodyParam = synthetic` — the template
`{{#bodyParam}}` block will pick it up automatically.

### Enum `defaultValue` is pre-formatted by the parent

For `isEnum` properties, `AbstractJavaCodegen` formats `defaultValue` as
`"TypeName.CONSTANT"` (e.g. `"StatusEnum.ACTIVE"`) before `postProcessAllModels()` runs.
`formatDefaultValue()` must therefore return `val` directly for enum properties without
any transformation — applying `.toUpperCase()` or prepending `datatypeWithEnum + "."` a
second time would produce double-qualified names like `StatusEnum.STATUSENUM.ACTIVE`.

### `getTemplateDir()` / `embeddedTemplateDir()` do not exist as overrides

These look like obvious override candidates but they are not virtual methods in 7.x.
Template resolution uses the `templateDir` and `embeddedTemplateDir` **fields** (set
directly in the constructor). Adding `@Override` on non-existent methods causes a
compilation error.

---

## 9. Generated project structure

```
output-dir/
├── pom.xml                                          Maven project (Helidon BOM, APT, service plugin)
└── src/main/
    ├── java/{apiPackage}/
    │   ├── {Tag}Api.java                            @Http.Path interface
    │   ├── {Tag}Endpoint.java                       @RestServer.Endpoint implementation stub
    │   ├── {Tag}Client.java                         @RestClient.Endpoint stub
    │   ├── {Tag}Exception.java                      RuntimeException with Status
    │   └── {Tag}ErrorHandler.java                   @Service.Singleton ErrorHandler
    ├── java/{modelPackage}/
    │   └── {Model}.java                             Helidon build-time JSON binding POJO
    ├── java/{invokerPackage}/
    │   └── Main.java                                @Service.GenerateBinding entry point
    └── resources/
        ├── application.yaml                         server.port + security stub
        └── logging.properties                       JUL configuration
```

The generated `pom.xml` configures two Helidon-specific build steps:

1. **`helidon-bundles-apt`** annotation processor — runs during `compile`, reads
   `@RestServer.Endpoint` / `@Service.Singleton` etc. and generates service descriptor
   files and routing wiring classes.

2. **`helidon-service-maven-plugin` `create-application` goal** — runs during `package`,
   generates `ApplicationBinding.java` that wires all discovered services into a single
   class. This eliminates classpath scanning at runtime and enables deterministic startup.

Optional dependencies are added to the generated `pom.xml` when the corresponding
generator option is enabled:

| Option | Added dependency |
|--------|-----------------|
| `corsEnabled` | `io.helidon.webserver:helidon-webserver-cors` |
| `ftEnabled` | `io.helidon.fault-tolerance:helidon-fault-tolerance` |
| `tracingEnabled` | `io.helidon.tracing:helidon-tracing` |
| `metricsEnabled` | `io.helidon.metrics:helidon-metrics-api` |
| `serveOpenApi` | `io.helidon.openapi:helidon-openapi` |

---

## 10. Testing strategy

Tests live in `src/test/` and run with `mvn test`. The suite contains 111 tests across
9 test classes.

### Unit tests — `HelidonSeDeclarativeCodegenTest` (22 tests)

Instantiates `HelidonSeDeclarativeCodegen` directly. No file I/O, no spec parsing.
Covers:

- Generator metadata (`getName`, `getTag`, `getHelp`)
- Naming: `toApiName` for empty/null/hyphenated/multi-word input; `toModelName` for the
  `Error → ApiError` mapping
- `apiFilename` for each of the five template names
- Template map registration (api, model, doc/test emptiness)
- Default package values and option defaults

### Integration tests

Each integration test class follows the same pattern: a `@BeforeAll` method runs the full
openapi-generator pipeline against a bundled spec file (written to a `@TempDir`), and
`@Test` methods assert on the generated file content.

| Class | Spec | Tests | What it covers |
|-------|------|-------|----------------|
| `PetstoreGenerationIT` | `petstore.yaml` | 35 | File existence, endpoint annotations, optional params, `@RestServer.ComputedHeader`, path params, return types, `@RestServer.Status`, API interface, model POJOs (`@Json.Entity`, `@Json.Required`), model name remapping, pom.xml, Main.java |
| `FeaturesGenerationIT` | `features.yaml` | 16 | `@Deprecated`, Javadoc from summary/description, inner enum class and constants, field type uses enum, default value initialisers (enum, int, double), property Javadoc, param `@Validation.String.Length`, `@Validation.Integer.Min/Max`, Validation import |
| `FormGenerationIT` | `form.yaml` | 7 | `@Http.Consumes(APPLICATION_FORM_URLENCODED_VALUE)`, `Parameters formBody`, Parameters import, `@Http.Consumes(MULTIPART_FORM_DATA_VALUE)`, `ReadableEntity formBody`, ReadableEntity import |
| `ValidationGenerationIT` | `petstore.yaml` (validation spec) | 9 | `@Validation.*` on model properties, `@Validation.Validated` on model class, Validation import |
| `SecurityGenerationIT` | security spec | 10 | `@RoleValidator.Roles`, SecurityContext param, security import |
| `CorsGenerationIT` | `petstore.yaml` | 3 | `@Cors.Defaults`, Cors import, `helidon-webserver-cors` in pom.xml |
| `FtGenerationIT` | `petstore.yaml` | 3 | `@Ft.Retry`, Ft import, `helidon-fault-tolerance` in pom.xml |
| `ObservabilityGenerationIT` | `petstore.yaml` | 6 | `@Tracing.Traced`, Tracing import, `helidon-tracing`, `@Metrics.Timed`, Metrics import, `helidon-metrics-api` |

The spec path is resolved via `Paths.get(resource.toURI())` rather than
`resource.getFile()` to avoid the Windows leading-slash issue (`/C:/...` is not a valid
Windows path but is what `getFile()` returns on that platform).

---

## 11. Extension points

The generator is a standard Java class. Common customisation points:

**Add a new per-tag template** — register it in `apiTemplateFiles` in `processOpts()` and
add a corresponding `.mustache` file in `src/main/resources/helidon-se-declarative/`.
Override `apiFilename()` to add the new template name → output filename mapping.

**Add a new template variable** — put a key into the `OperationsMap` inside
`postProcessOperationsWithModels()`, then reference it as `{{myKey}}` in the template.
For per-operation variables, add to `op.vendorExtensions` in `fromOperation()` and use
`{{vendorExtensions.myKey}}` in the template.

**Change generated class names** — override `toApiName()` and `apiFilename()` together.
For model names, add entries to `modelNameMapping` in the constructor.

**Add a new global file** — add a `SupportingFile` entry in `processOpts()` and create
the corresponding `.mustache` file.

**Handle a new OpenAPI feature** — enrich operations in `fromOperation()` or models in
`fromModel()` / `postProcessAllModels()` via vendor extensions, then consume them in
templates.

---

## 12. Future work

**Upstream contribution** — this generator is a candidate for contribution to
openapi-generator as an addition or replacement for `JavaHelidonServerCodegen`'s
declarative target. Target: after the Helidon SE declarative API reaches GA.

**`@RestClient.Endpoint` import** — the exact import path for `@RestClient.Endpoint` was
not confirmed stable in 4.4.0-M2. The generated `{Tag}Client.java` has the annotation
commented out with instructions; once the import path is confirmed, uncomment it and
remove the note.

**`oneOf` / `anyOf` / `allOf`** — discriminated unions and schema inheritance are not yet
handled. Properties from composed schemas are not inlined; a TODO comment is the current
fallback.

**`additionalProperties` (free-form maps)** — schemas with `additionalProperties: true`
or a typed schema are not yet emitted as `Map<String, T>` fields.

**File download** — operations with `produces: application/octet-stream` currently use
`byte[]` as a return type. A proper streaming return type may be preferable for large
responses.

**Path-level parameters** — parameters defined on the path item (not the operation) are
not yet promoted into each operation's parameter list.

**Cookie parameters** — OpenAPI `in: cookie` parameters have no direct Helidon SE
declarative annotation equivalent. Currently skipped; could be handled by injecting
`ServerRequest` and extracting cookies manually.
