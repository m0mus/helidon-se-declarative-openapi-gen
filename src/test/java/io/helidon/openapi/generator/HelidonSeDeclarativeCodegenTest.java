package io.helidon.openapi.generator;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openapitools.codegen.CodegenType;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link HelidonSeDeclarativeCodegen} — verifies naming conventions,
 * metadata, and template registration without running the full generation pipeline.
 */
class HelidonSeDeclarativeCodegenTest {

    private HelidonSeDeclarativeCodegen codegen;

    @BeforeEach
    void setUp() {
        codegen = new HelidonSeDeclarativeCodegen();
    }

    // -------------------------------------------------------------------------
    // Metadata
    // -------------------------------------------------------------------------

    @Test
    void getName_returnsCorrectId() {
        assertThat(codegen.getName()).isEqualTo("helidon-se-declarative");
    }

    @Test
    void getTag_isServer() {
        assertThat(codegen.getTag()).isEqualTo(CodegenType.SERVER);
    }

    @Test
    void getHelp_isNotBlank() {
        assertThat(codegen.getHelp()).isNotBlank();
    }

    // -------------------------------------------------------------------------
    // toApiName
    // -------------------------------------------------------------------------

    @Test
    void toApiName_simpleTag_returnsCamelCase() {
        assertThat(codegen.toApiName("pets")).isEqualTo("Pets");
    }

    @Test
    void toApiName_emptyTag_returnsDefault() {
        assertThat(codegen.toApiName("")).isEqualTo("Default");
    }

    @Test
    void toApiName_nullTag_returnsDefault() {
        assertThat(codegen.toApiName(null)).isEqualTo("Default");
    }

    @Test
    void toApiName_hyphenatedTag_returnsCamelCase() {
        assertThat(codegen.toApiName("pet-store")).isEqualTo("PetStore");
    }

    @Test
    void toApiName_multiWordTag_returnsCamelCase() {
        assertThat(codegen.toApiName("store orders")).isEqualTo("StoreOrders");
    }

    // -------------------------------------------------------------------------
    // toModelName
    // -------------------------------------------------------------------------

    @Test
    void toModelName_errorMappedToApiError() {
        // "Error" clashes with java.lang.Error — must be remapped
        assertThat(codegen.toModelName("Error")).isEqualTo("ApiError");
    }

    @Test
    void toModelName_petUnchanged() {
        assertThat(codegen.toModelName("Pet")).isEqualTo("Pet");
    }

    // -------------------------------------------------------------------------
    // apiFilename
    // -------------------------------------------------------------------------

    @Test
    void apiFilename_apiMustache_producesEndpointJava() {
        String filename = codegen.apiFilename("api.mustache", "pets");
        assertThat(filename).endsWith("PetsEndpoint.java");
    }

    @Test
    void apiFilename_apiInterfaceMustache_producesApiJava() {
        String filename = codegen.apiFilename("api-interface.mustache", "pets");
        assertThat(filename).endsWith("PetsApi.java");
    }

    @Test
    void apiFilename_restClientMustache_producesClientJava() {
        String filename = codegen.apiFilename("restClient.mustache", "pets");
        assertThat(filename).endsWith("PetsClient.java");
    }

    @Test
    void apiFilename_apiExceptionMustache_producesExceptionJava() {
        String filename = codegen.apiFilename("apiException.mustache", "pets");
        assertThat(filename).endsWith("PetsException.java");
    }

    @Test
    void apiFilename_errorHandlerMustache_producesErrorHandlerJava() {
        String filename = codegen.apiFilename("errorHandler.mustache", "pets");
        assertThat(filename).endsWith("PetsErrorHandler.java");
    }

    // -------------------------------------------------------------------------
    // Template registration
    // -------------------------------------------------------------------------

    @Test
    void constructor_registersApiAndApiInterfaceTemplates() {
        assertThat(codegen.apiTemplateFiles())
                .containsKey("api.mustache")
                .containsKey("api-interface.mustache");
    }

    @Test
    void constructor_registersModelTemplate() {
        assertThat(codegen.modelTemplateFiles()).containsKey("model.mustache");
    }

    @Test
    void constructor_clearsDocAndTestTemplates() {
        assertThat(codegen.modelDocTemplateFiles()).isEmpty();
        assertThat(codegen.apiDocTemplateFiles()).isEmpty();
        assertThat(codegen.apiTestTemplateFiles()).isEmpty();
        assertThat(codegen.modelTestTemplateFiles()).isEmpty();
    }

    // -------------------------------------------------------------------------
    // Defaults
    // -------------------------------------------------------------------------

    @Test
    void defaultPackages_matchExpectedValues() {
        assertThat(codegen.apiPackage()).isEqualTo("io.helidon.example.api");
        assertThat(codegen.modelPackage()).isEqualTo("io.helidon.example.model");
        assertThat(codegen.getInvokerPackage()).isEqualTo("io.helidon.example");
    }
}
