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
 * Integration test — verifies that {@code tracingEnabled=true} and {@code metricsEnabled=true}
 * add the expected observability annotations to generated endpoint classes.
 */
class ObservabilityGenerationIT {

    @TempDir
    static Path outputDir;

    @BeforeAll
    static void generate() throws Exception {
        URL resource = ObservabilityGenerationIT.class
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
                .addAdditionalProperty("tracingEnabled", "true")
                .addAdditionalProperty("metricsEnabled", "true");

        new DefaultGenerator().opts(configurator.toClientOptInput()).generate();
    }

    // -------------------------------------------------------------------------
    // Tracing
    // -------------------------------------------------------------------------

    @Test
    void endpoint_hasTracingTracedAnnotation() throws IOException {
        assertThat(read(apiFile("PetsEndpoint.java")))
                .contains("@Tracing.Traced");
    }

    @Test
    void endpoint_importsTracing() throws IOException {
        assertThat(read(apiFile("PetsEndpoint.java")))
                .contains("import io.helidon.tracing.Tracing;");
    }

    @Test
    void pom_hasTracingDependency() throws IOException {
        assertThat(read(outputDir.resolve("pom.xml").toFile()))
                .contains("helidon-tracing");
    }

    // -------------------------------------------------------------------------
    // Metrics
    // -------------------------------------------------------------------------

    @Test
    void endpoint_hasMetricsTimedAnnotation() throws IOException {
        assertThat(read(apiFile("PetsEndpoint.java")))
                .contains("@Metrics.Timed");
    }

    @Test
    void endpoint_importsMetrics() throws IOException {
        assertThat(read(apiFile("PetsEndpoint.java")))
                .contains("import io.helidon.metrics.api.Metrics;");
    }

    @Test
    void pom_hasMetricsDependency() throws IOException {
        assertThat(read(outputDir.resolve("pom.xml").toFile()))
                .contains("helidon-metrics-api");
    }

    private File apiFile(String name) {
        return outputDir.resolve("src/main/java/io/helidon/example/api/" + name).toFile();
    }

    private String read(File file) throws IOException {
        return Files.readString(file.toPath());
    }
}
