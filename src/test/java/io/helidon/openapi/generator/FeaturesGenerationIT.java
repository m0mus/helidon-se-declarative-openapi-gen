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
 * Integration test — verifies gap features: deprecated, Javadoc, enums,
 * default values, nullable, and parameter-level validation.
 */
class FeaturesGenerationIT {

    @TempDir
    static Path outputDir;

    @BeforeAll
    static void generate() throws Exception {
        URL resource = FeaturesGenerationIT.class
                .getClassLoader()
                .getResource("features.yaml");
        String specPath = Paths.get(resource.toURI()).toAbsolutePath().toString();

        CodegenConfigurator configurator = new CodegenConfigurator()
                .setGeneratorName("helidon-se-declarative")
                .setInputSpec(specPath)
                .setOutputDir(outputDir.toString())
                .addAdditionalProperty("helidonVersion", "4.4.0-M2")
                .addAdditionalProperty("apiPackage", "io.helidon.example.api")
                .addAdditionalProperty("modelPackage", "io.helidon.example.model")
                .addAdditionalProperty("invokerPackage", "io.helidon.example");

        new DefaultGenerator().opts(configurator.toClientOptInput()).generate();
    }

    // -------------------------------------------------------------------------
    // deprecated: true → @Deprecated on operation method
    // -------------------------------------------------------------------------

    @Test
    void endpoint_deprecatedOperation_hasDeprecatedAnnotation() throws IOException {
        assertThat(read(apiFile("ThingsEndpoint.java")))
                .contains("@Deprecated");
    }

    @Test
    void apiInterface_deprecatedOperation_hasDeprecatedAnnotation() throws IOException {
        assertThat(read(apiFile("ThingsApi.java")))
                .contains("@Deprecated");
    }

    // -------------------------------------------------------------------------
    // summary / description → Javadoc
    // -------------------------------------------------------------------------

    @Test
    void endpoint_operation_hasJavadocFromSummary() throws IOException {
        assertThat(read(apiFile("ThingsEndpoint.java")))
                .contains("List all things");
    }

    @Test
    void endpoint_operation_hasJavadocFromDescription() throws IOException {
        assertThat(read(apiFile("ThingsEndpoint.java")))
                .contains("Returns a paginated list of things");
    }

    @Test
    void apiInterface_operation_hasJavadocFromSummary() throws IOException {
        assertThat(read(apiFile("ThingsApi.java")))
                .contains("List all things");
    }

    // -------------------------------------------------------------------------
    // Enum property → inner enum class in model
    // -------------------------------------------------------------------------

    @Test
    void model_enumProperty_hasInnerEnumClass() throws IOException {
        String content = read(modelFile("Thing.java"));
        assertThat(content).contains("public enum StatusEnum");
    }

    @Test
    void model_enumProperty_hasEnumConstants() throws IOException {
        String content = read(modelFile("Thing.java"));
        assertThat(content)
                .contains("ACTIVE")
                .contains("INACTIVE")
                .contains("PENDING");
    }

    @Test
    void model_enumProperty_fieldUsesEnumType() throws IOException {
        assertThat(read(modelFile("Thing.java")))
                .contains("StatusEnum status");
    }

    // -------------------------------------------------------------------------
    // Default values → field initializers
    // -------------------------------------------------------------------------

    @Test
    void model_stringEnumDefault_hasInitializer() throws IOException {
        // status has default: active → StatusEnum.ACTIVE
        assertThat(read(modelFile("Thing.java")))
                .contains("StatusEnum.ACTIVE");
    }

    @Test
    void model_integerDefault_hasInitializer() throws IOException {
        // count has default: 0
        assertThat(read(modelFile("Thing.java")))
                .contains("= 0");
    }

    @Test
    void model_doubleDefault_hasInitializer() throws IOException {
        // score has default: 1.0
        assertThat(read(modelFile("Thing.java")))
                .contains("= 1.0");
    }

    // -------------------------------------------------------------------------
    // Property Javadoc from description
    // -------------------------------------------------------------------------

    @Test
    void model_property_hasJavadocFromDescription() throws IOException {
        assertThat(read(modelFile("Thing.java")))
                .contains("Unique identifier");
    }

    // -------------------------------------------------------------------------
    // Parameter-level validation annotations
    // -------------------------------------------------------------------------

    @Test
    void endpoint_stringParam_hasLengthValidation() throws IOException {
        assertThat(read(apiFile("ThingsEndpoint.java")))
                .contains("@Validation.String.Length(min = 1, value = 20)");
    }

    @Test
    void endpoint_intParam_hasMinMaxValidation() throws IOException {
        String content = read(apiFile("ThingsEndpoint.java"));
        assertThat(content)
                .contains("@Validation.Integer.Min(1)")
                .contains("@Validation.Integer.Max(100)");
    }

    @Test
    void endpoint_paramValidation_importsValidation() throws IOException {
        assertThat(read(apiFile("ThingsEndpoint.java")))
                .contains("import io.helidon.validation.Validation;");
    }

    @Test
    void apiInterface_stringParam_hasLengthValidation() throws IOException {
        assertThat(read(apiFile("ThingsApi.java")))
                .contains("@Validation.String.Length(min = 1, value = 20)");
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

    private String read(File file) throws IOException {
        return Files.readString(file.toPath());
    }
}
