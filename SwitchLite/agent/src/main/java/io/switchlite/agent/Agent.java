package io.switchlite.agent;

import java.lang.instrument.Instrumentation;
import java.util.jar.JarFile;

/**
 * Sandwich Architecture - Java Agent Entry Point
 * Layer 2: Class loading bytecode manipulation, mapping provider
 */
public class Agent {
    
    private static Instrumentation instrumentation;
    
    /**
     * Agentmain entry point for runtime attachment
     */
    public static void agentmain(String agentArgs, Instrumentation inst) {
        System.out.println("[SwitchLite Agent] Attached to running JVM");
        init(agentArgs, inst);
    }
    
    /**
     * Premain entry point for startup attachment
     */
    public static void premain(String agentArgs, Instrumentation inst) {
        System.out.println("[SwitchLite Agent] Loaded at JVM startup");
        init(agentArgs, inst);
    }
    
    private static void init(String agentArgs, Instrumentation inst) {
        instrumentation = inst;
        
        // Parse arguments (platform, version, mappings path)
        String[] args = parseAgentArgs(agentArgs);
        String platform = args[0];
        String version = args[1];
        String mappingsPath = args[2];
        
        System.out.println("[Agent] Platform: " + platform);
        System.out.println("[Agent] Version: " + version);
        System.out.println("[Agent] Mappings: " + mappingsPath);
        
        // Load mapping library
        try {
            MappingLoader.loadMappings(platform, version, mappingsPath);
        } catch (Exception e) {
            System.err.println("[Agent] Failed to load mappings: " + e.getMessage());
            e.printStackTrace();
            return;
        }
        
        // Register class transformer
        inst.addTransformer(new Transformer());
        System.out.println("[Agent] Transformer registered successfully");
        
        // Initialize MappingContext with loaded mappings
        MappingContext.initialize();
        
        System.out.println("[SwitchLite Agent] Initialization complete");
    }
    
    private static String[] parseAgentArgs(String args) {
        if (args == null || args.isEmpty()) {
            return new String[]{"Unknown", "Unknown", "./mappings"};
        }
        return args.split("\\|");
    }
}
