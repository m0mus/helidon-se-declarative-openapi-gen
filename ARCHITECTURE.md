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
        │   ├── HelidonSeDeclarativeCodegenTest.java   Unit tests (19)
        │   └── PetstoreGenerationIT.java              Integration tests (34)
        └── resources/
            └── petstore.yaml                  Spec used by integration tests
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
     │  removes swagger 1.x annotation import names ("ApiModel", "ApiModelProperty")
     ▼
HelidonSeDeclarativeCodegen.fromOperation(path, method, operation, servers)
     │  converts each operation → CodegenOperation
     │  enriches with vendorExtensions:
     │    x-http-annotation, x-status-code, x-has-response-headers,
     │    x-response-headers, x-security-roles, x-has-security-roles
     │  wraps optional query params in Optional<T>
     ▼
HelidonSeDeclarativeCodegen.postProcessAllModels(objs)
     │  marks required properties with x-json-required vendorExtension
     ▼
HelidonSeDeclarativeCodegen.postProcessOperationsWithModels(objs, allModels)
     │  groups operations by tag → one OperationsMap per tag
     │  computes helidonBasePath (common path prefix without path-param segments)
     │  per-operation: x-method-path, x-has-method-path, null-safe returnType
     │  accumulates: hasResponseHeaders, hasOptionalQueryParams, errorModel
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

### `toApiName(String name)`

Returns the class name prefix used for all per-tag files. The override returns plain
camelCase (`"Pets"`) rather than the default which appends `"Api"` (`"PetsApi"`). This
keeps the naming readable: `PetsEndpoint`, `PetsApi`, `PetsClient`, `PetsException`.
Empty or null input → `"Default"`.

### `apiFilename(String templateName, String tag)`

Maps each template name to its output filename. A switch expression handles the five
possible templates; anything else delegates to the parent. This is necessary because the
parent assumes a single api template and would produce `PetsEndpoint.java` for all five.

### `preprocessOpenAPI(OpenAPI openAPI)`

Runs before any per-operation processing. Extracts the path component from
`servers[0].url` (e.g. `/v1` from `https://api.example.com/v1`) and stores it as
`additionalProperties["serverBasePath"]`. This value feeds the `application.yaml.mustache`
stub and can be used as a base path prefix.

### `fromModel(String name, Schema schema)`

Calls the parent and then removes swagger 1.x annotation shorthand names (`"ApiModel"`,
`"ApiModelProperty"`, `"Schema"`) from `model.imports`. The parent populates these by
default; they would resolve to `io.swagger.annotations.*` imports that are not on the
Helidon runtime classpath.

### `fromOperation(String path, String httpMethod, Operation operation, List<Server> servers)`

Calls the parent and then enriches `op.vendorExtensions`:

| Key | Value |
|-----|-------|
| `x-http-annotation` | `"@Http.GET"` / `"@Http.POST"` / etc. |
| `x-status-code` | Non-200 success status integer (e.g. `201`), if present |
| `x-has-response-headers` | `true` if any 2xx response declares headers |
| `x-response-headers` | List of `{name: "x-next"}` maps |
| `x-security-roles` | List of scope strings from `security` requirements |
| `x-has-security-roles` | `true` if roles list is non-empty |

Also mutates `param.dataType` to `Optional<T>` for optional query parameters, and
stores the original type as `x-bare-type`.

### `postProcessOperationsWithModels(OperationsMap objs, List<ModelMap> allModels)`

Called once per tag group. Key responsibilities:

1. **Computes `helidonBasePath`** — the longest common path prefix shared by all
   operations in the tag. This becomes the `@Http.Path` on the endpoint class. The method
   name is deliberately different from `"basePath"` to avoid a context collision (see
   [section 8](#8-non-obvious-implementation-details)).

2. **Per-operation sub-path** — strips the common prefix from each operation's full path
   and stores it as `x-method-path`. Empty sub-paths are not written to avoid redundant
   `@Http.Path("")` annotations.

3. **Accumulates boolean flags** `hasResponseHeaders` and `hasOptionalQueryParams` across
   all operations in the tag, so templates can conditionally emit imports once per class
   rather than once per method.

4. **Finds `errorModel`** — the data type of the first non-2xx response, used by
   `errorHandler.mustache` to name the schema type in the comment.

5. **Exposes `classname` and `classnameLowercase`** for templates that need them outside
   the `{{#operations}}` block.

### `postProcessAllModels(Map<String, ModelsMap> objs)`

Iterates all model properties and sets `x-json-required = true` on any property marked
`required: true` in the spec. The template uses this to emit `@JsonProperty(required = true)`.

### `computeCommonPath(List<CodegenOperation> ops)`

Private helper. Splits each operation path into segments and finds the longest common
leading segment sequence that does not include path-parameter segments (`{...}`). Returns
`"/"` if no common static prefix exists. Example:

```
/pets       → common prefix: /pets
/pets/{id}
```

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
  `processOpts()`. Available in every template without a surrounding block.
- **`OperationsMap`** — per-tag; wraps `List<CodegenOperation>` under the `operations`
  key and is also used as a flat map for tag-level variables like `helidonBasePath`,
  `hasResponseHeaders`, `errorModel`, and `classname`.

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

### Thin jar distribution via the Maven plugin

The generator is published as a plain jar with no special packaging. Users consume it as a
`<dependency>` inside the `openapi-generator-maven-plugin` block in their own `pom.xml`.
Maven resolves all transitive dependencies from the repository. This follows the same
pattern used by every other custom openapi-generator plugin and aligns with Helidon's own
distribution approach, which does not use fat jars.

### No Helidon runtime dependency in the generator

`openapi-gen/pom.xml` depends only on openapi-generator. Helidon itself is a **generated
output** dependency, not an input to the generator. This keeps the generator's classpath
small and avoids any Helidon-version lock-in at the generator level.

### One endpoint class per OpenAPI tag

Operations are grouped by their first tag. Un-tagged operations go into `Default*`. This
mirrors how Helidon endpoints are typically structured: one class per resource. The
alternative (all operations in one class) would produce unreadably large files for
multi-resource APIs.

### `{Tag}Api` interface as shared HTTP contract

The HTTP annotations (`@Http.Path`, `@Http.GET`, `@Http.QueryParam`, …) are declared once
on the interface rather than duplicated on the endpoint class. This follows the Helidon
declarative pattern where the interface defines the contract and the implementation class
inherits it. The REST client (`{Tag}Client`) extends the same interface, ensuring the
client and server are always in sync.

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
`{{{dataType}}}`, `{{{datatypeWithEnum}}}`.

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

### Swagger annotation imports

`AbstractJavaCodegen` unconditionally adds `"ApiModel"` and `"ApiModelProperty"` to
`CodegenModel.imports`. These short names resolve via an internal mapping to
`io.swagger.annotations.ApiModel` / `io.swagger.annotations.ApiModelProperty` — classes
from the Swagger 1.x annotation library that is not on the Helidon classpath.

Fix: override `fromModel()` and call `model.imports.remove("ApiModel")` etc. before
returning. This must happen in `fromModel()` (before the short names are resolved to full
import paths) — post-processing at the `ModelsMap` level would see the already-resolved
package names, which are harder to enumerate.

### `fromOperation` signature change in 7.x

openapi-generator 7.x changed the `fromOperation` override signature. The parameters
`Map<String, Schema> allDefinitions` and `OpenAPI openAPI` were removed; the full OpenAPI
model is now accessible as `this.openAPI` (a protected field on the parent). The correct
4-argument signature is:

```java
public CodegenOperation fromOperation(String path, String httpMethod,
                                      Operation operation, List<Server> servers)
```

### `getTemplateDir()` / `embeddedTemplateDir()` do not exist

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
    │   └── {Model}.java                             Jackson POJO
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

---

## 10. Testing strategy

Tests live in `src/test/` and run with `mvn test`.

### Unit tests — `HelidonSeDeclarativeCodegenTest`

Instantiates `HelidonSeDeclarativeCodegen` directly. No file I/O, no spec parsing.
Covers:

- Generator metadata (`getName`, `getTag`, `getHelp`)
- Naming: `toApiName` for empty/null/hyphenated/multi-word input; `toModelName` for the
  `Error → ApiError` mapping
- `apiFilename` for each of the five template names
- Template map registration (api, model, doc/test emptiness)
- Default package values

### Integration tests — `PetstoreGenerationIT`

Runs the full openapi-generator pipeline programmatically against a bundled test spec and
asserts on the output written to a JUnit 5 `@TempDir`. Covers:

- **Existence** of all 11 expected output files
- **Content** of the endpoint class: Helidon annotations, `Optional<T>` for optional
  query params, `ServerResponse` injection when a success response declares headers,
  `@Http.PathParam` for path parameters, correct return types, `@RestServer.Status` for
  non-200 success codes
- **API interface**: is an interface with the correct `@Http.Path`
- **Model classes**: `@JsonInclude`, `@JsonProperty(required = true)` on required fields,
  correct field types, getters/setters, no swagger imports
- **Model name remapping**: `Error` schema → `ApiError` class
- **`pom.xml`**: Helidon version and `helidon-webserver` dependency present
- **`Main.java`**: `@Service.GenerateBinding` annotation present

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
`fromModel()` via vendor extensions, then consume them in templates.

---

## 12. Future work

**Upstream contribution** — this generator is a candidate for contribution to
openapi-generator as an addition or replacement for `JavaHelidonServerCodegen`'s
declarative target.

**`@RestClient.Endpoint` import** — the exact import path for `@RestClient.Endpoint` was
not confirmed stable in 4.4.0-M1. The generated `{Tag}Client.java` has the annotation
commented out with instructions; once the import path is confirmed, uncomment it and
remove the note.

**Security integration** — `x-security-roles` is computed and stored as a vendor
extension, but the templates do not yet emit `@RoleValidator.Roles`. Completing this
requires deciding on a security provider and verifying the annotation's import path in the
target Helidon version.

**Cookie parameters** — OpenAPI `in: cookie` parameters have no direct Helidon SE
declarative annotation equivalent. Currently skipped; could be handled by injecting
`ServerRequest` and extracting cookies manually.
