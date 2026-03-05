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
 * Integration test — verifies form request body support:
 * application/x-www-form-urlencoded and multipart/form-data.
 */
class FormGenerationIT {

    @TempDir
    static Path outputDir;

    @BeforeAll
    static void generate() throws Exception {
        URL resource = FormGenerationIT.class
                .getClassLoader()
                .getResource("form.yaml");
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
    // application/x-www-form-urlencoded
    // -------------------------------------------------------------------------

    @Test
    void endpoint_formUrlEncoded_hasCorrectConsumes() throws IOException {
        assertThat(read(apiFile("FormsEndpoint.java")))
                .contains("@Http.Consumes(MediaTypes.APPLICATION_FORM_URLENCODED_VALUE)");
    }

    @Test
    void endpoint_formUrlEncoded_hasParametersBodyParam() throws IOException {
        assertThat(read(apiFile("FormsEndpoint.java")))
                .contains("Parameters formBody");
    }

    @Test
    void endpoint_formUrlEncoded_importsParameters() throws IOException {
        assertThat(read(apiFile("FormsEndpoint.java")))
                .contains("import io.helidon.http.Parameters;");
    }

    @Test
    void apiInterface_formUrlEncoded_hasCorrectConsumes() throws IOException {
        assertThat(read(apiFile("FormsApi.java")))
                .contains("@Http.Consumes(MediaTypes.APPLICATION_FORM_URLENCODED_VALUE)");
    }

    // -------------------------------------------------------------------------
    // multipart/form-data
    // -------------------------------------------------------------------------

    @Test
    void endpoint_multipart_hasCorrectConsumes() throws IOException {
        assertThat(read(apiFile("FormsEndpoint.java")))
                .contains("@Http.Consumes(MediaTypes.MULTIPART_FORM_DATA_VALUE)");
    }

    @Test
    void endpoint_multipart_hasReadableEntityBodyParam() throws IOException {
        assertThat(read(apiFile("FormsEndpoint.java")))
                .contains("ReadableEntity formBody");
    }

    @Test
    void endpoint_multipart_importsReadableEntity() throws IOException {
        assertThat(read(apiFile("FormsEndpoint.java")))
                .contains("import io.helidon.http.media.ReadableEntity;");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private File apiFile(String name) {
        return outputDir.resolve("src/main/java/io/helidon/example/api/" + name).toFile();
    }

    private String read(File file) throws IOException {
        return Files.readString(file.toPath());
    }
}
