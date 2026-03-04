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
 * Integration test — verifies that {@code corsEnabled=true} adds {@code @Cors.Defaults} to
 * generated endpoint classes and includes {@code helidon-webserver-cors} in pom.xml.
 */
class CorsGenerationIT {

    @TempDir
    static Path outputDir;

    @BeforeAll
    static void generate() throws Exception {
        URL resource = CorsGenerationIT.class
                .getClassLoader()
                .getResource("petstore.yaml");
        String specPath = Paths.get(resource.toURI()).toAbsolutePath().toString();

        CodegenConfigurator configurator = new CodegenConfigurator()
                .setGeneratorName("helidon-se-declarative")
                .setInputSpec(specPath)
                .setOutputDir(outputDir.toString())
                .addAdditionalProperty("helidonVersion", "4.4.0-M2")
                .addAdditionalProperty("apiPackage", "io.helidon.example.api")
                .addAdditionalProperty("modelPackage", "io.helidon.example.model")
                .addAdditionalProperty("invokerPackage", "io.helidon.example")
                .addAdditionalProperty("corsEnabled", "true");

        new DefaultGenerator().opts(configurator.toClientOptInput()).generate();
    }

    @Test
    void endpoint_hasCorsDefaultsAnnotation() throws IOException {
        assertThat(read(apiFile("PetsEndpoint.java")))
                .contains("@Cors.Defaults");
    }

    @Test
    void endpoint_importsCors() throws IOException {
        assertThat(read(apiFile("PetsEndpoint.java")))
                .contains("import io.helidon.webserver.cors.Cors;");
    }

    @Test
    void pom_hasCorsDependency() throws IOException {
        assertThat(read(outputDir.resolve("pom.xml").toFile()))
                .contains("helidon-webserver-cors");
    }

    private File apiFile(String name) {
        return outputDir.resolve("src/main/java/io/helidon/example/api/" + name).toFile();
    }

    private String read(File file) throws IOException {
        return Files.readString(file.toPath());
    }
}
