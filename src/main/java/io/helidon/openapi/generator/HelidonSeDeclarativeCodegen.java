package io.helidon.openapi.generator;

import java.io.File;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
 * Plus per model: {Model}.java (Jackson POJO)
 * Plus supporting files: pom.xml, Main.java, application.yaml, logging.properties
 * </p>
 */
public class HelidonSeDeclarativeCodegen extends AbstractJavaCodegen {

    static final String OPT_HELIDON_VERSION = "helidonVersion";
    static final String OPT_GENERATE_CLIENT = "generateClient";
    static final String OPT_GENERATE_ERROR_HANDLER = "generateErrorHandler";
    static final String OPT_SERVE_OPENAPI = "serveOpenApi";
    static final String OPT_SERVE_BASE_PATH = "serveBasePath";

    private String helidonVersion = "4.4.0-M1";
    private boolean generateClient = true;
    private boolean generateErrorHandler = true;
    private boolean serveOpenApi = true;
    private String serveBasePath = "";

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

        // Expose options to all templates via additionalProperties
        additionalProperties.put("helidonVersion", helidonVersion);
        additionalProperties.put("generateClient", generateClient);
        additionalProperties.put("generateErrorHandler", generateErrorHandler);
        additionalProperties.put("serveOpenApi", serveOpenApi);
        additionalProperties.put("serveBasePath", serveBasePath);

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
                    // Response headers on 2xx → need ServerResponse injection
                    if (statusCode >= 200 && statusCode < 300
                            && response.getHeaders() != null
                            && !response.getHeaders().isEmpty()) {
                        op.vendorExtensions.put("x-has-response-headers", Boolean.TRUE);
                        List<Map<String, String>> headerList = new ArrayList<>();
                        response.getHeaders().forEach((headerName, header) -> {
                            Map<String, String> h = new HashMap<>();
                            h.put("name", headerName);
                            headerList.add(h);
                        });
                        op.vendorExtensions.put("x-response-headers", headerList);
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
        boolean anyResponseHeaders = false;
        boolean anyOptionalQuery = false;
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
            if (op.vendorExtensions.containsKey("x-has-response-headers")) {
                anyResponseHeaders = true;
            }
            if (op.allParams.stream().anyMatch(p -> p.vendorExtensions.containsKey("x-optional"))) {
                anyOptionalQuery = true;
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

        result.put("hasResponseHeaders", anyResponseHeaders);
        result.put("hasOptionalQueryParams", anyOptionalQuery);
        result.put("errorModel", errorModel != null ? errorModel : "Object");

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
    // Per-model post-processing: mark required properties for @JsonProperty
    // -------------------------------------------------------------------------

    @Override
    public Map<String, ModelsMap> postProcessAllModels(Map<String, ModelsMap> objs) {
        Map<String, ModelsMap> result = super.postProcessAllModels(objs);
        result.forEach((name, modelsMap) ->
                modelsMap.getModels().forEach(modelContainer -> {
                    var model = modelContainer.getModel();

                    // Mark required properties for @JsonProperty(required = true)
                    for (CodegenProperty prop : model.vars) {
                        if (prop.required) {
                            prop.vendorExtensions.put("x-json-required", Boolean.TRUE);
                        }
                    }

                    // Swagger annotation imports are already excluded via fromModel() override.
                }));
        return result;
    }
}
