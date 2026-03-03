package io.helidon.openapi.generator;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openapitools.codegen.DefaultGenerator;
import org.openapitools.codegen.config.CodegenConfigurator;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test — runs the full openapi-generator pipeline against
 * {@code petstore.yaml} and asserts that the expected files are generated
 * with correct content.
 */
class PetstoreGenerationIT {

    @TempDir
    static Path outputDir;

    /** Path to petstore.yaml bundled in test resources. */
    private static String specPath;

    @BeforeAll
    static void generate() throws Exception {
        URL resource = PetstoreGenerationIT.class
                .getClassLoader()
                .getResource("petstore.yaml");
        specPath = Paths.get(resource.toURI()).toAbsolutePath().toString();

        CodegenConfigurator configurator = new CodegenConfigurator()
                .setGeneratorName("helidon-se-declarative")
                .setInputSpec(specPath)
                .setOutputDir(outputDir.toString())
                .addAdditionalProperty("helidonVersion", "4.4.0-M1")
                .addAdditionalProperty("apiPackage", "io.helidon.example.api")
                .addAdditionalProperty("modelPackage", "io.helidon.example.model")
                .addAdditionalProperty("invokerPackage", "io.helidon.example");

        new DefaultGenerator().opts(configurator.toClientOptInput()).generate();
    }

    // -------------------------------------------------------------------------
    // File existence
    // -------------------------------------------------------------------------

    @Test
    void pomXml_isGenerated() {
        assertThat(outputDir.resolve("pom.xml")).exists();
    }

    @Test
    void mainJava_isGenerated() {
        assertThat(javaFile("io/helidon/example/Main.java")).exists();
    }

    @Test
    void applicationYaml_isGenerated() {
        assertThat(resourceFile("application.yaml")).exists();
    }

    @Test
    void loggingProperties_isGenerated() {
        assertThat(resourceFile("logging.properties")).exists();
    }

    @Test
    void petsEndpoint_isGenerated() {
        assertThat(apiFile("PetsEndpoint.java")).exists();
    }

    @Test
    void petsApi_isGenerated() {
        assertThat(apiFile("PetsApi.java")).exists();
    }

    @Test
    void petsClient_isGenerated() {
        assertThat(apiFile("PetsClient.java")).exists();
    }

    @Test
    void petsException_isGenerated() {
        assertThat(apiFile("PetsException.java")).exists();
    }

    @Test
    void petsErrorHandler_isGenerated() {
        assertThat(apiFile("PetsErrorHandler.java")).exists();
    }

    @Test
    void petModel_isGenerated() {
        assertThat(modelFile("Pet.java")).exists();
    }

    @Test
    void apiErrorModel_isGenerated() {
        // "Error" schema → renamed to "ApiError"
        assertThat(modelFile("ApiError.java")).exists();
    }

    // -------------------------------------------------------------------------
    // PetsEndpoint.java content
    // -------------------------------------------------------------------------

    @Test
    void endpoint_hasRestServerEndpointAnnotation() throws IOException {
        assertThat(read(apiFile("PetsEndpoint.java")))
                .contains("@RestServer.Endpoint");
    }

    @Test
    void endpoint_hasServiceSingletonAnnotation() throws IOException {
        assertThat(read(apiFile("PetsEndpoint.java")))
                .contains("@Service.Singleton");
    }

    @Test
    void endpoint_hasHttpPathAnnotation() throws IOException {
        assertThat(read(apiFile("PetsEndpoint.java")))
                .contains("@Http.Path(\"/pets\")");
    }

    @Test
    void endpoint_listPets_hasGetAnnotation() throws IOException {
        assertThat(read(apiFile("PetsEndpoint.java")))
                .contains("@Http.GET");
    }

    @Test
    void endpoint_createPets_hasPostAnnotation() throws IOException {
        assertThat(read(apiFile("PetsEndpoint.java")))
                .contains("@Http.POST");
    }

    @Test
    void endpoint_createPets_hasStatus201() throws IOException {
        assertThat(read(apiFile("PetsEndpoint.java")))
                .contains("@RestServer.Status(201)");
    }

    @Test
    void endpoint_listPets_hasOptionalLimitParam() throws IOException {
        String content = read(apiFile("PetsEndpoint.java"));
        assertThat(content).contains("Optional<Integer>");
        assertThat(content).contains("@Http.QueryParam(\"limit\")");
    }

    @Test
    void endpoint_listPets_hasServerResponseForHeader() throws IOException {
        // listPets has x-next response header → ServerResponse injected
        String content = read(apiFile("PetsEndpoint.java"));
        assertThat(content).contains("ServerResponse");
    }

    @Test
    void endpoint_showPetById_hasPathParam() throws IOException {
        assertThat(read(apiFile("PetsEndpoint.java")))
                .contains("@Http.PathParam(\"petId\")");
    }

    @Test
    void endpoint_listPets_returnsListOfPet() throws IOException {
        assertThat(read(apiFile("PetsEndpoint.java")))
                .contains("List<Pet>");
    }

    // -------------------------------------------------------------------------
    // PetsApi.java content
    // -------------------------------------------------------------------------

    @Test
    void apiInterface_isAnInterface() throws IOException {
        assertThat(read(apiFile("PetsApi.java")))
                .contains("public interface PetsApi");
    }

    @Test
    void apiInterface_hasHttpPathAnnotation() throws IOException {
        assertThat(read(apiFile("PetsApi.java")))
                .contains("@Http.Path(\"/pets\")");
    }

    // -------------------------------------------------------------------------
    // Pet.java model content
    // -------------------------------------------------------------------------

    @Test
    void petModel_hasCorrectPackage() throws IOException {
        assertThat(read(modelFile("Pet.java")))
                .contains("package io.helidon.example.model;");
    }

    @Test
    void petModel_hasJsonIncludeAnnotation() throws IOException {
        assertThat(read(modelFile("Pet.java")))
                .contains("@JsonInclude(JsonInclude.Include.NON_NULL)");
    }

    @Test
    void petModel_requiredFields_haveJsonPropertyRequired() throws IOException {
        String content = read(modelFile("Pet.java"));
        // id and name are required in petstore.yaml
        assertThat(content).contains("@JsonProperty(required = true)");
    }

    @Test
    void petModel_hasIdNameTagFields() throws IOException {
        String content = read(modelFile("Pet.java"));
        assertThat(content)
                .contains("Long id")
                .contains("String name")
                .contains("String tag");
    }

    @Test
    void petModel_hasGettersAndSetters() throws IOException {
        String content = read(modelFile("Pet.java"));
        assertThat(content)
                .contains("getId()")
                .contains("setId(")
                .contains("getName()")
                .contains("setName(");
    }

    @Test
    void petModel_noSwaggerImports() throws IOException {
        String content = read(modelFile("Pet.java"));
        assertThat(content)
                .doesNotContain("io.swagger.annotations")
                .doesNotContain("ApiModel")
                .doesNotContain("ApiModelProperty");
    }

    // -------------------------------------------------------------------------
    // ApiError.java model content
    // -------------------------------------------------------------------------

    @Test
    void apiErrorModel_isNamedApiError() throws IOException {
        assertThat(read(modelFile("ApiError.java")))
                .contains("public class ApiError");
    }

    @Test
    void apiErrorModel_hasCodeAndMessageFields() throws IOException {
        String content = read(modelFile("ApiError.java"));
        assertThat(content)
                .contains("Integer code")
                .contains("String message");
    }

    // -------------------------------------------------------------------------
    // pom.xml content
    // -------------------------------------------------------------------------

    @Test
    void pomXml_containsHelidonVersion() throws IOException {
        assertThat(read(outputDir.resolve("pom.xml").toFile()))
                .contains("4.4.0-M1");
    }

    @Test
    void pomXml_containsHelidonWebserver() throws IOException {
        assertThat(read(outputDir.resolve("pom.xml").toFile()))
                .contains("helidon-webserver");
    }

    // -------------------------------------------------------------------------
    // Main.java content
    // -------------------------------------------------------------------------

    @Test
    void mainJava_hasGenerateBindingAnnotation() throws IOException {
        assertThat(read(javaFile("io/helidon/example/Main.java")))
                .contains("@Service.GenerateBinding");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private File apiFile(String name) {
        return outputDir.resolve("src/main/java/io/helidon/example/api/" + name).toFile();
    }

    private File modelFile(String name) {
        return outputDir.resolve("src/main/java/io/helidon/example/model/" + name).toFile();
    }

    private File javaFile(String relativePath) {
        return outputDir.resolve("src/main/java/" + relativePath).toFile();
    }

    private File resourceFile(String name) {
        return outputDir.resolve("src/main/resources/" + name).toFile();
    }

    private String read(File file) throws IOException {
        return Files.readString(file.toPath());
    }
}
