package io.helidon.openapi.generator;

import java.io.File;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.openapitools.codegen.CodegenModel;
import org.openapitools.codegen.CodegenOperation;
import org.openapitools.codegen.CodegenParameter;
import org.openapitools.codegen.CodegenProperty;
import org.openapitools.codegen.CodegenResponse;
import org.openapitools.codegen.CodegenType;
import org.openapitools.codegen.SupportingFile;

import io.swagger.v3.oas.models.media.Schema;
import org.openapitools.codegen.languages.AbstractJavaCodegen;
import org.openapitools.codegen.model.ModelMap;
import org.openapitools.codegen.model.ModelsMap;
import org.openapitools.codegen.model.OperationMap;
import org.openapitools.codegen.model.OperationsMap;

import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.servers.Server;

import static org.openapitools.codegen.utils.StringUtils.camelize;

/**
 * openapi-generator SPI implementation that generates Helidon SE 4.x declarative code.
 *
 * <p>Register via SPI: {@code META-INF/services/org.openapitools.codegen.CodegenConfig}
 * pointing to this class. Use with {@code -g helidon-se-declarative}.</p>
 *
 * <p>Generates per tag group:
 * <ul>
 *   <li>{Tag}Api.java — {@code @Http.Path} interface (shared contract)</li>
 *   <li>{Tag}Endpoint.java — {@code @RestServer.Endpoint @Service.Singleton} implementation</li>
 *   <li>{Tag}Client.java — {@code @RestClient.Endpoint} interface (if generateClient=true)</li>
 *   <li>{Tag}Exception.java — RuntimeException subclass (if generateErrorHandler=true)</li>
 *   <li>{Tag}ErrorHandler.java — ErrorHandlerProvider (if generateErrorHandler=true)</li>
 * </ul>
 * Plus per model: {Model}.java (Helidon build-time JSON binding POJO)
 * Plus supporting files: pom.xml, Main.java, application.yaml, logging.properties
 * </p>
 */
public class HelidonSeDeclarativeCodegen extends AbstractJavaCodegen {

    static final String OPT_HELIDON_VERSION = "helidonVersion";
    static final String OPT_GENERATE_CLIENT = "generateClient";
    static final String OPT_GENERATE_ERROR_HANDLER = "generateErrorHandler";
    static final String OPT_SERVE_OPENAPI = "serveOpenApi";
    static final String OPT_SERVE_BASE_PATH = "serveBasePath";
    static final String OPT_CORS_ENABLED = "corsEnabled";
    static final String OPT_FT_ENABLED = "ftEnabled";
    static final String OPT_TRACING_ENABLED = "tracingEnabled";
    static final String OPT_METRICS_ENABLED = "metricsEnabled";

    private String helidonVersion = "4.4.0-M2";
    private boolean generateClient = true;
    private boolean generateErrorHandler = true;
    private boolean serveOpenApi = true;
    private String serveBasePath = "";
    private boolean corsEnabled = false;
    private boolean ftEnabled = false;
    private boolean tracingEnabled = false;
    private boolean metricsEnabled = false;

    public HelidonSeDeclarativeCodegen() {
        super();

        outputFolder = "generated-code/helidon-se-declarative";
        // Setting the fields directly configures where templates are resolved from
        templateDir = "helidon-se-declarative";
        embeddedTemplateDir = "helidon-se-declarative";

        apiPackage = "io.helidon.example.api";
        modelPackage = "io.helidon.example.model";
        invokerPackage = "io.helidon.example";

        // Default model name mapping: "Error" clashes with java.lang.Error
        modelNameMapping.put("Error", "ApiError");


        // Clear doc/test templates inherited from AbstractJavaCodegen — not generated
        modelDocTemplateFiles.clear();
        apiDocTemplateFiles.clear();
        apiTestTemplateFiles.clear();
        modelTestTemplateFiles.clear();

        // Templates for per-API-tag files
        apiTemplateFiles.clear();  // clear any defaults first
        apiTemplateFiles.put("api.mustache", "Endpoint.java");
        apiTemplateFiles.put("api-interface.mustache", "Api.java");

        // Template for per-model files
        modelTemplateFiles.put("model.mustache", ".java");

        // Supporting files (processed as Mustache templates)
        supportingFiles.add(new SupportingFile("pom.xml.mustache", "", "pom.xml"));
        supportingFiles.add(new SupportingFile("application.yaml.mustache",
                "src/main/resources", "application.yaml"));
        supportingFiles.add(new SupportingFile("logging.properties.mustache",
                "src/main/resources", "logging.properties"));

        // Generator options
        addOption(OPT_HELIDON_VERSION,
                "Helidon version written into the generated pom.xml",
                helidonVersion);
        addOption(OPT_GENERATE_CLIENT,
                "Generate @RestClient.Endpoint interface per tag",
                String.valueOf(generateClient));
        addOption(OPT_GENERATE_ERROR_HANDLER,
                "Generate Exception + ErrorHandlerProvider classes per tag",
                String.valueOf(generateErrorHandler));
        addOption(OPT_SERVE_OPENAPI,
                "Copy spec to META-INF/openapi.yaml and add helidon-openapi dependency",
                String.valueOf(serveOpenApi));
        addOption(OPT_SERVE_BASE_PATH,
                "Base path prefix to add in front of all endpoint paths (e.g. /v1)",
                serveBasePath);
        addOption(OPT_CORS_ENABLED,
                "Add @Cors.Defaults to every endpoint class (enables CORS via application.yaml configuration)",
                String.valueOf(corsEnabled));
        addOption(OPT_FT_ENABLED,
                "Add @Ft.Retry to every generated REST client interface (enables automatic retries)",
                String.valueOf(ftEnabled));
        addOption(OPT_TRACING_ENABLED,
                "Add @Tracing.Traced to every endpoint class (creates spans for all endpoint methods)",
                String.valueOf(tracingEnabled));
        addOption(OPT_METRICS_ENABLED,
                "Add @Metrics.Timed to every endpoint method (records invocation timing)",
                String.valueOf(metricsEnabled));
    }

    // -------------------------------------------------------------------------
    // Metadata
    // -------------------------------------------------------------------------

    @Override
    public String getName() {
        return "helidon-se-declarative";
    }

    @Override
    public CodegenType getTag() {
        return CodegenType.SERVER;
    }

    @Override
    public String getHelp() {
        return "Generates a Helidon SE 4.x declarative server using @RestServer.Endpoint annotations.";
    }

    // -------------------------------------------------------------------------
    // Option processing
    // -------------------------------------------------------------------------

    @Override
    public void processOpts() {
        super.processOpts();

        if (additionalProperties.containsKey(OPT_HELIDON_VERSION)) {
            helidonVersion = additionalProperties.get(OPT_HELIDON_VERSION).toString();
        }
        if (additionalProperties.containsKey(OPT_GENERATE_CLIENT)) {
            generateClient = Boolean.parseBoolean(
                    additionalProperties.get(OPT_GENERATE_CLIENT).toString());
        }
        if (additionalProperties.containsKey(OPT_GENERATE_ERROR_HANDLER)) {
            generateErrorHandler = Boolean.parseBoolean(
                    additionalProperties.get(OPT_GENERATE_ERROR_HANDLER).toString());
        }
        if (additionalProperties.containsKey(OPT_SERVE_OPENAPI)) {
            serveOpenApi = Boolean.parseBoolean(
                    additionalProperties.get(OPT_SERVE_OPENAPI).toString());
        }
        if (additionalProperties.containsKey(OPT_SERVE_BASE_PATH)) {
            serveBasePath = additionalProperties.get(OPT_SERVE_BASE_PATH).toString();
        }
        if (additionalProperties.containsKey(OPT_CORS_ENABLED)) {
            corsEnabled = Boolean.parseBoolean(
                    additionalProperties.get(OPT_CORS_ENABLED).toString());
        }
        if (additionalProperties.containsKey(OPT_FT_ENABLED)) {
            ftEnabled = Boolean.parseBoolean(
                    additionalProperties.get(OPT_FT_ENABLED).toString());
        }
        if (additionalProperties.containsKey(OPT_TRACING_ENABLED)) {
            tracingEnabled = Boolean.parseBoolean(
                    additionalProperties.get(OPT_TRACING_ENABLED).toString());
        }
        if (additionalProperties.containsKey(OPT_METRICS_ENABLED)) {
            metricsEnabled = Boolean.parseBoolean(
                    additionalProperties.get(OPT_METRICS_ENABLED).toString());
        }

        // Expose options to all templates via additionalProperties
        additionalProperties.put("helidonVersion", helidonVersion);
        additionalProperties.put("generateClient", generateClient);
        additionalProperties.put("generateErrorHandler", generateErrorHandler);
        additionalProperties.put("serveOpenApi", serveOpenApi);
        additionalProperties.put("serveBasePath", serveBasePath);
        additionalProperties.put("corsEnabled", corsEnabled);
        additionalProperties.put("ftEnabled", ftEnabled);
        additionalProperties.put("tracingEnabled", tracingEnabled);
        additionalProperties.put("metricsEnabled", metricsEnabled);

        // Conditionally add per-tag template files
        if (generateClient) {
            apiTemplateFiles.put("restClient.mustache", "Client.java");
        }
        if (generateErrorHandler) {
            apiTemplateFiles.put("apiException.mustache", "Exception.java");
            apiTemplateFiles.put("errorHandler.mustache", "ErrorHandler.java");
        }

        // Main.java location depends on the (possibly user-supplied) invokerPackage
        String mainFolder = "src/main/java/" + invokerPackage.replace('.', '/');
        supportingFiles.add(new SupportingFile("Main.java.mustache", mainFolder, "Main.java"));
    }

    // -------------------------------------------------------------------------
    // Naming: use plain camelCase (no "Api" suffix) so classname = "Pets", not "PetsApi"
    // -------------------------------------------------------------------------

    @Override
    public String toApiName(String name) {
        if (name == null || name.isEmpty()) {
            return "Default";
        }
        return camelize(sanitizeName(name));
    }

    /**
     * Custom filename per template so the output naming matches the plan:
     * <ul>
     *   <li>api.mustache          → {Tag}Endpoint.java</li>
     *   <li>api-interface.mustache → {Tag}Api.java</li>
     *   <li>restClient.mustache   → {Tag}Client.java</li>
     *   <li>apiException.mustache → {Tag}Exception.java</li>
     *   <li>errorHandler.mustache → {Tag}ErrorHandler.java</li>
     * </ul>
     */
    @Override
    public String apiFilename(String templateName, String tag) {
        String base = camelize(sanitizeName(tag));
        String folder = apiFileFolder();
        return switch (templateName) {
            case "api.mustache"           -> folder + File.separator + base + "Endpoint.java";
            case "api-interface.mustache" -> folder + File.separator + base + "Api.java";
            case "restClient.mustache"    -> folder + File.separator + base + "Client.java";
            case "apiException.mustache"  -> folder + File.separator + base + "Exception.java";
            case "errorHandler.mustache"  -> folder + File.separator + base + "ErrorHandler.java";
            default                       -> super.apiFilename(templateName, tag);
        };
    }

    // -------------------------------------------------------------------------
    // Spec pre-processing: extract server base path
    // -------------------------------------------------------------------------

    @Override
    public void preprocessOpenAPI(io.swagger.v3.oas.models.OpenAPI openAPI) {
        super.preprocessOpenAPI(openAPI);

        if (openAPI.getServers() != null && !openAPI.getServers().isEmpty()) {
            String serverUrl = openAPI.getServers().get(0).getUrl();
            try {
                URI uri = new URI(serverUrl);
                String path = uri.getPath();
                if (path != null && !path.isEmpty() && !"/".equals(path)) {
                    additionalProperties.put("serverBasePath", path);
                    // Only set serveBasePath from URL if not explicitly configured
                    if (serveBasePath.isEmpty()) {
                        additionalProperties.put("serveBasePath", path);
                    }
                }
            } catch (Exception ignored) {
                // Malformed URL — skip
            }
        }
    }

    // -------------------------------------------------------------------------
    // Per-model: strip swagger 1.x annotation imports (not on Helidon SE classpath)
    // -------------------------------------------------------------------------

    @Override
    @SuppressWarnings("rawtypes")
    public CodegenModel fromModel(String name, Schema schema) {
        CodegenModel model = super.fromModel(name, schema);
        // AbstractJavaCodegen adds "ApiModel" / "ApiModelProperty" shorthand names to
        // codegenModel.imports when annotationLibrary == SWAGGER2.  These resolve via
        // importMapping to io.swagger.annotations.* which is not on the Helidon classpath.
        model.imports.remove("ApiModel");
        model.imports.remove("ApiModelProperty");
        model.imports.remove("Schema");   // also remove OpenAPI 3 schema annotation shorthand
        model.imports.remove("JsonInclude");
        model.imports.remove("JsonProperty");
        model.imports.remove("JsonNullable");   // openApiNullable wrapper — not used in our template
        return model;
    }

    // -------------------------------------------------------------------------
    // Per-operation enrichment
    // -------------------------------------------------------------------------

    @Override
    public CodegenOperation fromOperation(String path,
                                          String httpMethod,
                                          Operation operation,
                                          List<Server> servers) {
        CodegenOperation op = super.fromOperation(path, httpMethod, operation, servers);

        // HTTP method annotation string (e.g. "@Http.GET")
        op.vendorExtensions.put("x-http-annotation", "@Http." + httpMethod.toUpperCase());

        // Mark optional query parameters so the template can wrap them in Optional<>
        for (CodegenParameter param : op.allParams) {
            if (param.isQueryParam && !param.required) {
                param.vendorExtensions.put("x-optional", Boolean.TRUE);
                param.vendorExtensions.put("x-bare-type", param.dataType);
                param.dataType = "Optional<" + param.dataType + ">";
            }
        }

        // Success status override (e.g. 201 for createPets)
        if (operation.getResponses() != null) {
            operation.getResponses().forEach((code, response) -> {
                if ("default".equals(code)) return;
                try {
                    int statusCode = Integer.parseInt(code);
                    if (statusCode > 200 && statusCode < 300) {
                        op.vendorExtensions.put("x-status-code", statusCode);
                    }
                    // Response headers on 2xx → @RestServer.Header (static) or @RestServer.ComputedHeader (dynamic)
                    if (statusCode >= 200 && statusCode < 300
                            && response.getHeaders() != null
                            && !response.getHeaders().isEmpty()) {
                        List<Map<String, String>> staticHeaders = new ArrayList<>();
                        List<Map<String, String>> computedHeaders = new ArrayList<>();
                        response.getHeaders().forEach((headerName, header) -> {
                            Object defaultVal = header.getSchema() != null
                                    ? header.getSchema().getDefault() : null;
                            if (defaultVal != null) {
                                Map<String, String> h = new HashMap<>();
                                h.put("name", headerName);
                                h.put("value", defaultVal.toString());
                                staticHeaders.add(h);
                            } else {
                                Map<String, String> h = new HashMap<>();
                                h.put("name", headerName);
                                h.put("functionName", headerNameToFunctionName(headerName));
                                computedHeaders.add(h);
                            }
                        });
                        if (!staticHeaders.isEmpty()) {
                            op.vendorExtensions.put("x-has-static-headers", Boolean.TRUE);
                            op.vendorExtensions.put("x-static-headers", staticHeaders);
                        }
                        if (!computedHeaders.isEmpty()) {
                            op.vendorExtensions.put("x-has-computed-headers", Boolean.TRUE);
                            op.vendorExtensions.put("x-computed-headers", computedHeaders);
                        }
                        if (!staticHeaders.isEmpty() || !computedHeaders.isEmpty()) {
                            op.vendorExtensions.put("x-has-response-headers", Boolean.TRUE);
                        }
                    }
                } catch (NumberFormatException ignored) {
                    // Non-numeric code — skip
                }
            });
        }

        // Security roles (scopes) from operation-level security requirements
        if (operation.getSecurity() != null && !operation.getSecurity().isEmpty()) {
            List<String> roles = new ArrayList<>();
            operation.getSecurity().forEach(req ->
                    req.forEach((scheme, scopes) -> roles.addAll(scopes)));
            if (!roles.isEmpty()) {
                op.vendorExtensions.put("x-security-roles", roles);
                op.vendorExtensions.put("x-has-security-roles", Boolean.TRUE);
                // Pre-format as Java annotation value: single → "role", multiple → {"r1", "r2"}
                String rolesValue = roles.size() == 1
                        ? "\"" + roles.get(0) + "\""
                        : "{" + roles.stream()
                                .map(r -> "\"" + r + "\"")
                                .collect(Collectors.joining(", ")) + "}";
                op.vendorExtensions.put("x-roles-annotation-value", rolesValue);
            }
        }

        return op;
    }

    // -------------------------------------------------------------------------
    // Per-tag post-processing: compute paths, error model, optional-param flags
    // -------------------------------------------------------------------------

    @Override
    public OperationsMap postProcessOperationsWithModels(OperationsMap objs,
                                                         List<ModelMap> allModels) {
        OperationsMap result = super.postProcessOperationsWithModels(objs, allModels);
        OperationMap ops = result.getOperations();
        List<CodegenOperation> opList = ops.getOperation();
        if (opList == null || opList.isEmpty()) {
            return result;
        }

        // Compute the common path prefix for the @Http.Path class annotation.
        // NOTE: "basePath" is already used by additionalProperties (full server URL) and
        // would be overridden when DefaultGenerator merges additionalProperties into the
        // template context. Use a Helidon-specific key instead.
        String commonPath = computeCommonPath(opList);
        result.put("helidonBasePath", commonPath);

        // Per-operation: method-level sub-path and other enrichments
        boolean anyComputedHeaders = false;
        boolean anyOptionalQuery = false;
        boolean anySecurityRoles = false;
        boolean anyParamValidation = false;
        String errorModel = null;

        for (CodegenOperation op : opList) {
            // Sub-path (part after commonPath)
            String fullPath = op.path;
            String subPath = fullPath.startsWith(commonPath)
                    ? fullPath.substring(commonPath.length())
                    : fullPath;
            if (!subPath.isEmpty()) {
                op.vendorExtensions.put("x-method-path", subPath);
                op.vendorExtensions.put("x-has-method-path", Boolean.TRUE);
            }

            // Ensure returnType is never null (use "void" for no-body responses)
            if (op.returnType == null || op.returnType.isEmpty()) {
                op.returnType = "void";
            }
            op.vendorExtensions.put("x-return-type", op.returnType);
            op.vendorExtensions.put("x-is-void", "void".equals(op.returnType));

            // Accumulate flags
            if (op.vendorExtensions.containsKey("x-has-computed-headers")) {
                anyComputedHeaders = true;
            }
            if (op.allParams.stream().anyMatch(p -> p.vendorExtensions.containsKey("x-optional"))) {
                anyOptionalQuery = true;
            }
            // Parameter-level validation annotations
            for (CodegenParameter param : op.allParams) {
                List<Map<String, Object>> paramValidations = buildParamValidationAnnotations(param);
                if (!paramValidations.isEmpty()) {
                    param.vendorExtensions.put("x-validation-annotations", paramValidations);
                    anyParamValidation = true;
                }
            }
            if (op.vendorExtensions.containsKey("x-has-security-roles")) {
                anySecurityRoles = true;
                // SecurityContext needs a leading comma when other params already appear
                boolean needsLeadingComma = !op.allParams.isEmpty();
                op.vendorExtensions.put("x-needs-leading-comma-for-security", needsLeadingComma);
            }

            // Find error model from non-2xx responses
            if (errorModel == null) {
                for (CodegenResponse resp : op.responses) {
                    if ((resp.is4xx || resp.is5xx || resp.isDefault) && resp.dataType != null) {
                        errorModel = resp.dataType;
                        break;
                    }
                }
            }
        }

        result.put("hasComputedHeaders", anyComputedHeaders);
        result.put("hasOptionalQueryParams", anyOptionalQuery);
        result.put("hasSecurityRoles", anySecurityRoles);
        result.put("hasParamValidation", anyParamValidation);
        result.put("errorModel", errorModel != null ? errorModel : "Object");
        if (anySecurityRoles) {
            // Visible to supporting-file templates (pom.xml, application.yaml) which only
            // see additionalProperties, not the per-tag OperationsMap.
            additionalProperties.put("hasSecurity", Boolean.TRUE);
        }

        // Also expose classname in operations context for templates that need it
        String classname = toApiName(result.getOperations().get("baseName") != null
                ? result.getOperations().get("baseName").toString()
                : "Default");
        result.put("classname", classname);
        result.put("classnameLowercase", classname.toLowerCase());

        return result;
    }

    /**
     * Find the longest common path prefix shared by all operations in a tag group.
     * Stops before any path-parameter segment ({...}).
     */
    private String computeCommonPath(List<CodegenOperation> ops) {
        if (ops.isEmpty()) return "/";

        String first = ops.get(0).path;
        String[] firstParts = first.split("/", -1);

        // Find how many leading segments all operations share
        int commonSegments = firstParts.length;
        for (CodegenOperation op : ops) {
            String[] parts = op.path.split("/", -1);
            int match = 0;
            for (int i = 0; i < Math.min(commonSegments, parts.length); i++) {
                if (firstParts[i].equals(parts[i])) {
                    match = i + 1;
                } else {
                    break;
                }
            }
            commonSegments = Math.min(commonSegments, match);
        }

        // Build the common path, stopping before any path-parameter segments
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < commonSegments; i++) {
            if (firstParts[i].isEmpty()) continue;      // skip the leading ""
            if (firstParts[i].startsWith("{")) break;   // stop at path params
            sb.append("/").append(firstParts[i]);
        }
        return sb.length() == 0 ? "/" : sb.toString();
    }

    // -------------------------------------------------------------------------
    // Per-model post-processing: mark required properties and validation constraints
    // -------------------------------------------------------------------------

    @Override
    public Map<String, ModelsMap> postProcessAllModels(Map<String, ModelsMap> objs) {
        Map<String, ModelsMap> result = super.postProcessAllModels(objs);
        boolean anyValidation = false;
        for (Map.Entry<String, ModelsMap> entry : result.entrySet()) {
            ModelsMap modelsMap = entry.getValue();
            for (ModelMap modelContainer : modelsMap.getModels()) {
                var model = modelContainer.getModel();
                boolean modelHasValidation = false;

                for (CodegenProperty prop : model.vars) {
                    // Mark required properties for @Json.Required
                    if (prop.required) {
                        prop.vendorExtensions.put("x-json-required", Boolean.TRUE);
                    }

                    // Build @Validation.* annotations from OpenAPI constraints
                    List<Map<String, Object>> validationAnnotations = buildValidationAnnotations(prop);
                    if (!validationAnnotations.isEmpty()) {
                        prop.vendorExtensions.put("x-validation-annotations", validationAnnotations);
                        modelHasValidation = true;
                    }

                    // Format default value as a Java literal for field initializer
                    String javaDefault = formatDefaultValue(prop);
                    if (javaDefault != null) {
                        prop.vendorExtensions.put("x-default-value", javaDefault);
                    }
                }

                if (modelHasValidation) {
                    model.vendorExtensions.put("x-has-validations", Boolean.TRUE);
                    // The modelsMap-level "imports" list is already resolved to FQNs before
                    // postProcessAllModels() runs — add directly to it so the template picks it up.
                    @SuppressWarnings("unchecked")
                    List<Map<String, String>> importsList =
                            (List<Map<String, String>>) modelsMap.get("imports");
                    if (importsList != null) {
                        importsList.add(new HashMap<>(Map.of("import", "io.helidon.validation.Validation")));
                    }
                    anyValidation = true;
                }
            }
        }
        if (anyValidation) {
            additionalProperties.put("hasValidation", Boolean.TRUE);
        }
        return result;
    }

    /**
     * Converts a response header name to a camelCase {@code @RestServer.ComputedHeader} function name.
     * e.g. {@code "x-next"} → {@code "xNextHeaderFn"}, {@code "Cache-Control"} → {@code "cacheControlHeaderFn"}.
     */
    private String headerNameToFunctionName(String headerName) {
        String[] parts = headerName.split("-");
        StringBuilder sb = new StringBuilder(parts[0].toLowerCase());
        for (int i = 1; i < parts.length; i++) {
            if (!parts[i].isEmpty()) {
                sb.append(Character.toUpperCase(parts[i].charAt(0)));
                if (parts[i].length() > 1) sb.append(parts[i].substring(1).toLowerCase());
            }
        }
        sb.append("HeaderFn");
        return sb.toString();
    }

    /**
     * Maps OpenAPI schema constraints on a property to Helidon {@code @Validation.*} annotations.
     */
    private List<Map<String, Object>> buildValidationAnnotations(CodegenProperty prop) {
        List<Map<String, Object>> result = new ArrayList<>();

        if (prop.isString) {
            // minLength / maxLength → @Validation.String.Length
            if (prop.minLength != null || prop.maxLength != null) {
                List<String> attrs = new ArrayList<>();
                if (prop.minLength != null) attrs.add("min = " + prop.minLength);
                if (prop.maxLength != null) attrs.add("value = " + prop.maxLength);
                result.add(Map.of("annotation",
                        "@Validation.String.Length(" + String.join(", ", attrs) + ")"));
            }
            // pattern → @Validation.String.Pattern
            if (prop.pattern != null && !prop.pattern.isEmpty()) {
                String escaped = prop.pattern.replace("\\", "\\\\").replace("\"", "\\\"");
                result.add(Map.of("annotation", "@Validation.String.Pattern(\"" + escaped + "\")"));
            }
        } else if (prop.isInteger) {
            // minimum / maximum → @Validation.Integer.Min / Max
            if (prop.minimum != null) {
                try {
                    result.add(Map.of("annotation",
                            "@Validation.Integer.Min(" + (int) Double.parseDouble(prop.minimum) + ")"));
                } catch (NumberFormatException ignored) { }
            }
            if (prop.maximum != null) {
                try {
                    result.add(Map.of("annotation",
                            "@Validation.Integer.Max(" + (int) Double.parseDouble(prop.maximum) + ")"));
                } catch (NumberFormatException ignored) { }
            }
        } else if (prop.isLong) {
            // minimum / maximum → @Validation.Long.Min / Max
            if (prop.minimum != null) {
                try {
                    result.add(Map.of("annotation",
                            "@Validation.Long.Min(" + (long) Double.parseDouble(prop.minimum) + "L)"));
                } catch (NumberFormatException ignored) { }
            }
            if (prop.maximum != null) {
                try {
                    result.add(Map.of("annotation",
                            "@Validation.Long.Max(" + (long) Double.parseDouble(prop.maximum) + "L)"));
                } catch (NumberFormatException ignored) { }
            }
        } else if (prop.isNumber || prop.isFloat || prop.isDouble) {
            // minimum / maximum → @Validation.Number.Min / Max (string-valued)
            if (prop.minimum != null) {
                result.add(Map.of("annotation", "@Validation.Number.Min(\"" + prop.minimum + "\")"));
            }
            if (prop.maximum != null) {
                result.add(Map.of("annotation", "@Validation.Number.Max(\"" + prop.maximum + "\")"));
            }
        }

        // minItems / maxItems → @Validation.Collection.Size (independent of element type)
        if (prop.isArray && (prop.minItems != null || prop.maxItems != null)) {
            List<String> attrs = new ArrayList<>();
            if (prop.minItems != null) attrs.add("min = " + prop.minItems);
            if (prop.maxItems != null) attrs.add("value = " + prop.maxItems);
            result.add(Map.of("annotation",
                    "@Validation.Collection.Size(" + String.join(", ", attrs) + ")"));
        }

        return result;
    }

    /**
     * Formats a property's default value as a Java literal for use in a field initializer.
     * Returns {@code null} when no useful initializer can be produced (e.g. arrays).
     */
    private String formatDefaultValue(CodegenProperty prop) {
        if (prop.defaultValue == null || prop.defaultValue.isEmpty()) {
            return null;
        }
        String val = prop.defaultValue;
        if (prop.isEnum) {
            // Upstream AbstractJavaCodegen already formats the default as "TypeName.CONSTANT"
            return val;
        }
        if (prop.isString) {
            // Escape backslashes and double-quotes, then wrap in quotes
            return "\"" + val.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        }
        if (prop.isLong) {
            return val + "L";
        }
        if (prop.isFloat) {
            return val + "f";
        }
        if (prop.isArray || prop.isMap) {
            return null;  // skip — complex initialization
        }
        return val;  // integer, double, boolean — value as-is
    }

    /**
     * Maps OpenAPI schema constraints on a parameter to Helidon {@code @Validation.*} annotations.
     * Mirrors {@link #buildValidationAnnotations(CodegenProperty)} but operates on {@link CodegenParameter}.
     */
    private List<Map<String, Object>> buildParamValidationAnnotations(CodegenParameter param) {
        List<Map<String, Object>> result = new ArrayList<>();

        if (param.isString) {
            if (param.minLength != null || param.maxLength != null) {
                List<String> attrs = new ArrayList<>();
                if (param.minLength != null) attrs.add("min = " + param.minLength);
                if (param.maxLength != null) attrs.add("value = " + param.maxLength);
                result.add(Map.of("annotation",
                        "@Validation.String.Length(" + String.join(", ", attrs) + ")"));
            }
            if (param.pattern != null && !param.pattern.isEmpty()) {
                String escaped = param.pattern.replace("\\", "\\\\").replace("\"", "\\\"");
                result.add(Map.of("annotation", "@Validation.String.Pattern(\"" + escaped + "\")"));
            }
        } else if (param.isInteger) {
            if (param.minimum != null) {
                try {
                    result.add(Map.of("annotation",
                            "@Validation.Integer.Min(" + (int) Double.parseDouble(param.minimum) + ")"));
                } catch (NumberFormatException ignored) { }
            }
            if (param.maximum != null) {
                try {
                    result.add(Map.of("annotation",
                            "@Validation.Integer.Max(" + (int) Double.parseDouble(param.maximum) + ")"));
                } catch (NumberFormatException ignored) { }
            }
        } else if (param.isLong) {
            if (param.minimum != null) {
                try {
                    result.add(Map.of("annotation",
                            "@Validation.Long.Min(" + (long) Double.parseDouble(param.minimum) + "L)"));
                } catch (NumberFormatException ignored) { }
            }
            if (param.maximum != null) {
                try {
                    result.add(Map.of("annotation",
                            "@Validation.Long.Max(" + (long) Double.parseDouble(param.maximum) + "L)"));
                } catch (NumberFormatException ignored) { }
            }
        } else if (param.isNumber || param.isFloat || param.isDouble) {
            if (param.minimum != null) {
                result.add(Map.of("annotation", "@Validation.Number.Min(\"" + param.minimum + "\")"));
            }
            if (param.maximum != null) {
                result.add(Map.of("annotation", "@Validation.Number.Max(\"" + param.maximum + "\")"));
            }
        }

        if (param.isArray && (param.minItems != null || param.maxItems != null)) {
            List<String> attrs = new ArrayList<>();
            if (param.minItems != null) attrs.add("min = " + param.minItems);
            if (param.maxItems != null) attrs.add("value = " + param.maxItems);
            result.add(Map.of("annotation",
                    "@Validation.Collection.Size(" + String.join(", ", attrs) + ")"));
        }

        return result;
    }
}
