package io.helidon.openapi.generator;

import java.util.ArrayList;
import java.util.List;

import org.openapitools.codegen.DefaultGenerator;
import org.openapitools.codegen.config.CodegenConfigurator;

/**
 * Minimal CLI entry point for the Helidon SE declarative generator.
 *
 * <p>Usage:
 * <pre>
 *   java -jar target/...-shaded.jar generate \
 *     -g helidon-se-declarative \
 *     -i /path/to/spec.yaml \
 *     -o /path/to/output \
 *     [--additional-properties key=value,...]
 * </pre>
 * </p>
 */
public class GeneratorCli {

    public static void main(String[] args) {
        // Only "generate" command is supported
        if (args.length == 0 || !"generate".equals(args[0])) {
            System.err.println("Usage: generate -g <generatorName> -i <spec> -o <output> [--additional-properties k=v,...]");
            System.exit(1);
        }

        String generatorName = null;
        String inputSpec = null;
        String outputDir = null;
        List<String[]> additionalProps = new ArrayList<>();

        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "-g" -> generatorName = args[++i];
                case "-i" -> inputSpec = args[++i];
                case "-o" -> outputDir = args[++i];
                case "--additional-properties" -> {
                    for (String kv : args[++i].split(",")) {
                        String[] pair = kv.split("=", 2);
                        if (pair.length == 2) additionalProps.add(pair);
                    }
                }
                default -> System.err.println("Ignoring unknown arg: " + args[i]);
            }
        }

        if (generatorName == null || inputSpec == null || outputDir == null) {
            System.err.println("Missing required arguments: -g, -i, -o");
            System.exit(1);
        }

        CodegenConfigurator configurator = new CodegenConfigurator()
                .setGeneratorName(generatorName)
                .setInputSpec(inputSpec)
                .setOutputDir(outputDir);
        for (String[] kv : additionalProps) {
            configurator.addAdditionalProperty(kv[0], kv[1]);
        }

        new DefaultGenerator().opts(configurator.toClientOptInput()).generate();
    }
}
