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
 * Integration test — validates that OpenAPI schema constraints are mapped to
 * Helidon {@code @Validation.*} annotations in generated model classes.
 */
class ValidationGenerationIT {

    @TempDir
    static Path outputDir;

    @BeforeAll
    static void generate() throws Exception {
        URL resource = ValidationGenerationIT.class
                .getClassLoader()
                .getResource("validated.yaml");
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
    // @Validation.Validated on the class
    // -------------------------------------------------------------------------

    @Test
    void itemModel_hasValidatedAnnotation() throws IOException {
        assertThat(read(modelFile("Item.java")))
                .contains("@Validation.Validated");
    }

    @Test
    void itemModel_importsValidation() throws IOException {
        assertThat(read(modelFile("Item.java")))
                .contains("import io.helidon.validation.Validation;");
    }

    // -------------------------------------------------------------------------
    // String constraints: name (minLength=1, maxLength=100, pattern)
    // -------------------------------------------------------------------------

    @Test
    void itemModel_name_hasLengthAnnotation() throws IOException {
        assertThat(read(modelFile("Item.java")))
                .contains("@Validation.String.Length(min = 1, value = 100)");
    }

    @Test
    void itemModel_name_hasPatternAnnotation() throws IOException {
        assertThat(read(modelFile("Item.java")))
                .contains("@Validation.String.Pattern(");
    }

    // -------------------------------------------------------------------------
    // Integer constraints: quantity (minimum=0, maximum=1000)
    // -------------------------------------------------------------------------

    @Test
    void itemModel_quantity_hasMinAnnotation() throws IOException {
        assertThat(read(modelFile("Item.java")))
                .contains("@Validation.Integer.Min(0)");
    }

    @Test
    void itemModel_quantity_hasMaxAnnotation() throws IOException {
        assertThat(read(modelFile("Item.java")))
                .contains("@Validation.Integer.Max(1000)");
    }

    // -------------------------------------------------------------------------
    // Number constraints: price (minimum=0.01)
    // -------------------------------------------------------------------------

    @Test
    void itemModel_price_hasNumberMinAnnotation() throws IOException {
        assertThat(read(modelFile("Item.java")))
                .contains("@Validation.Number.Min(\"0.01\")");
    }

    // -------------------------------------------------------------------------
    // Array constraints: tags (minItems=1, maxItems=20)
    // -------------------------------------------------------------------------

    @Test
    void itemModel_tags_hasSizeAnnotation() throws IOException {
        assertThat(read(modelFile("Item.java")))
                .contains("@Validation.Collection.Size(min = 1, value = 20)");
    }

    // -------------------------------------------------------------------------
    // pom.xml: helidon-validation dependency
    // -------------------------------------------------------------------------

    @Test
    void pom_hasValidationDependency() throws IOException {
        assertThat(read(outputDir.resolve("pom.xml").toFile()))
                .contains("helidon-validation");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private File modelFile(String name) {
        return outputDir.resolve("src/main/java/io/helidon/example/model/" + name).toFile();
    }

    private String read(File file) throws IOException {
        return Files.readString(file.toPath());
    }
}
