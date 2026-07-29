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

    /**
     * Bootstrap entry for DLL injection + JNI (no Instrumentation).
     * Called by payload.dll via JNI CallStaticVoidMethod.
     * Transformer is a stub — we skip registration in this path.
     */
    public static void bootstrap(String configDir) {
        System.out.println("[SwitchLite Agent] Bootstrapped via JNI (no Instrumentation)");

        String platform = "Unknown";
        String version = "Unknown";
        File configFile = new File(configDir, "switchlite-config.properties");
        if (configFile.exists()) {
            Properties props = new Properties();
            try (FileInputStream fis = new FileInputStream(configFile)) {
                props.load(fis);
                platform = props.getProperty("switchlite.platform", "Unknown");
                version = props.getProperty("switchlite.version", "Unknown");
                System.out.println("[Agent] Config loaded from: " + configFile.getAbsolutePath());
            } catch (IOException e) {
                System.err.println("[Agent] Failed to read config: " + e.getMessage());
            }
        }

        System.out.println("[Agent] Platform: " + platform);
        System.out.println("[Agent] Version: " + version);

        String mappingsDir = detectMappingsDir();
        try {
            MappingLoader.loadMappings(platform, version, mappingsDir);
        } catch (Exception e) {
            System.err.println("[Agent] Failed to load mappings: " + e.getMessage());
            return;
        }

        // Transformer is a stub, skip registration
        System.out.println("[Agent] Skipping transformer (stub, no Instrumentation)");
        MappingContext.initialize();
        System.out.println("[SwitchLite Agent] Ready (JNI bootstrap)");

        // Verify injection by sending a chat message
        sendChatVerification();
    }

    // ── Chat verification ──

    private static void sendChatVerification() {
        try {
            // --- Step 1: Find Minecraft instance via runtime scanning ---
            Class<?> mcClass = Class.forName("net.minecraft.client.Minecraft");
            Method getMc = findStaticFactory(mcClass);
            if (getMc == null) {
                System.out.println("[Chat] No static Minecraft factory found");
                return;
            }
            Object mc = getMc.invoke(null);
            if (mc == null) { System.out.println("[Chat] MC instance is null"); return; }
            System.out.println("[Chat] MC via: " + getMc.getName());

            // --- Step 2: Find player field ---
            Field playerField = findFieldByType(mcClass, "EntityPlayer");
            if (playerField == null) {
                System.out.println("[Chat] Player field not found in Minecraft");
                return;
            }
            Object player = playerField.get(mc);
            if (player == null) { System.out.println("[Chat] Player is null"); return; }
            System.out.println("[Chat] Player via: " + playerField.getName());

            // --- Step 3: Walk hierarchy for addChatComponentMessage(IChatComponent) ---
            Method addChatMethod = findMethodInHierarchy(player.getClass(),
                new Predicate<Method>() {
                    public boolean test(Method m) {
                        if (m.getParameterCount() != 1) return false;
                        if (m.getReturnType() != void.class) return false;
                        String paramType = m.getParameterTypes()[0].getName();
                        return paramType.contains("ChatComponent") || paramType.contains("fj");
                    }
                });

            if (addChatMethod == null) {
                // Fallback: sendChatMessage(String) — goes to server, but works for verification
                System.out.println("[Chat] addChatComponentMessage not found, trying sendChatMessage...");
                addChatMethod = findMethodInHierarchy(player.getClass(),
                    new Predicate<Method>() {
                        public boolean test(Method m) {
                            return m.getParameterCount() == 1 &&
                                m.getParameterTypes()[0] == String.class &&
                                m.getReturnType() == void.class;
                        }
                    });
            }

            if (addChatMethod == null) {
                System.out.println("[Chat] No chat method found on player hierarchy");
                return;
            }

            // --- Step 4: Construct chat component or plain string ---
            Object msgArg;
            Class<?> paramType = addChatMethod.getParameterTypes()[0];

            if (paramType == String.class) {
                msgArg = "\u00a7a[SwitchLite] \u00a7fInjected! Press R for GUI";
            } else {
                // paramType is IChatComponent — need ChatComponentText(String)
                msgArg = findAndConstructChatComponent(
                    "\u00a7a[SwitchLite] \u00a7fInjected! Press R for GUI");
                if (msgArg == null) {
                    System.out.println("[Chat] Cannot construct ChatComponentText");
                    return;
                }
            }

            addChatMethod.invoke(player, msgArg);
            System.out.println("[Chat] Message sent via: " + addChatMethod.getName());
        } catch (Exception e) {
            System.err.println("[Agent] Chat verification failed: " + e.getMessage());
        }
    }

    // ── Reflection helpers ──

    /** Find a static no-arg factory method returning cls on given class. */
    private static Method findStaticFactory(Class<?> cls) {
        for (Method m : cls.getDeclaredMethods()) {
            if (m.getParameterCount() == 0 && m.getReturnType() == cls &&
                java.lang.reflect.Modifier.isStatic(m.getModifiers()))
                return m;
        }
        for (Method m : cls.getMethods()) {
            if (m.getParameterCount() == 0 && m.getReturnType() == cls &&
                java.lang.reflect.Modifier.isStatic(m.getModifiers()))
                return m;
        }
        return null;
    }

    /** Find a field whose type name contains [suffix] on given class. */
    private static Field findFieldByType(Class<?> cls, String suffix) {
        for (Field f : cls.getDeclaredFields()) {
            if (f.getType().getName().contains(suffix)) return f;
        }
        for (Field f : cls.getFields()) {
            if (f.getType().getName().contains(suffix)) return f;
        }
        return null;
    }

    /** Walk class hierarchy (self → parents) searching for a method matching predicate. */
    private static Method findMethodInHierarchy(Class<?> cls, Predicate<Method> pred) {
        Class<?> current = cls;
        while (current != null && current != Object.class) {
            for (Method m : current.getDeclaredMethods()) {
                if (pred.test(m)) return m;
            }
            current = current.getSuperclass();
        }
        return null;
    }

    /** Find ChatComponentText class and construct instance with given text. */
    private static Object findAndConstructChatComponent(String text) {
        // ChatComponentText is in net.minecraft.util (1.8.9) — try common classloaders
        try {
            Class<?> cct = Class.forName("net.minecraft.util.ChatComponentText");
            return cct.getConstructor(String.class).newInstance(text);
        } catch (Exception e) { /* fallthrough */ }

        // Try via game classloader (LaunchClassLoader)
        try {
            Class<?> cct = Class.forName("net.minecraft.util.ChatComponentText", true,
                Agent.class.getClassLoader());
            return cct.getConstructor(String.class).newInstance(text);
        } catch (Exception e) { /* fallthrough */ }

        return null;
    }

    /** Simple functional interface (no java.util.function in Java 8?). */
    private interface Predicate<T> {
        boolean test(T t);
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
