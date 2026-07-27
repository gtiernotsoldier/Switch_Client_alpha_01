package io.switchlite.agent;

import java.io.*;
import java.lang.instrument.Instrumentation;
import java.util.Properties;

/**
 * Sandwich Architecture - Java Agent Entry Point
 * Layer 2: Class loading bytecode manipulation, mapping provider
 */
public class Agent {

    private static Instrumentation instrumentation;

    public static void agentmain(String agentArgs, Instrumentation inst) {
        System.out.println("[SwitchLite Agent] Attached to running JVM");
        init(inst);
    }

    public static void premain(String agentArgs, Instrumentation inst) {
        System.out.println("[SwitchLite Agent] Loaded at JVM startup");
        init(inst);
    }

    private static void init(Instrumentation inst) {
        instrumentation = inst;

        // Read config from switchlite-config.properties (written by injector)
        String platform = "Unknown";
        String version = "Unknown";
        String configPath = detectConfigPath();

        if (configPath != null) {
            Properties props = new Properties();
            try (FileInputStream fis = new FileInputStream(configPath)) {
                props.load(fis);
                platform = props.getProperty("switchlite.platform", "Unknown");
                version = props.getProperty("switchlite.version", "Unknown");
                System.out.println("[Agent] Config loaded from: " + configPath);
            } catch (IOException e) {
                System.err.println("[Agent] Failed to read config: " + e.getMessage());
            }
        } else {
            System.err.println("[Agent] Config file not found, using defaults");
        }

        System.out.println("[Agent] Platform: " + platform);
        System.out.println("[Agent] Version: " + version);

        // Detect mappings directory (jar-embedded or external)
        String mappingsDir = detectMappingsDir();

        // Load mapping library
        try {
            MappingLoader.loadMappings(platform, version, mappingsDir);
        } catch (Exception e) {
            System.err.println("[Agent] Failed to load mappings: " + e.getMessage());
            e.printStackTrace();
            return;
        }

        // Register class transformer
        inst.addTransformer(new Transformer());
        System.out.println("[Agent] Transformer registered");

        // Initialize MappingContext
        MappingContext.initialize();
        System.out.println("[SwitchLite Agent] Ready");
    }

    /**
     * Detect config file path (next to agent.jar in %TEMP%).
     */
    private static String detectConfigPath() {
        String classpath = System.getProperty("java.class.path");
        if (classpath != null) {
            String[] paths = classpath.split(File.pathSeparator);
            if (paths.length > 0) {
                String jarDir = new File(paths[paths.length - 1]).getParent();
                if (jarDir != null) {
                    return jarDir + File.separator + "switchlite-config.properties";
                }
            }
        }
        return null;
    }

    /**
     * Detect mappings directory. Priority: jar-embedded > external ./mappings.
     */
    private static String detectMappingsDir() {
        try {
            InputStream test = Agent.class.getClassLoader()
                .getResourceAsStream("mappings/forge/v1_8_9.json");
            if (test != null) {
                test.close();
                System.out.println("[Agent] Using embedded mappings");
                return null; // classpath mode
            }
        } catch (Exception e) { /* ignore */ }
        return "./mappings";
    }
}
