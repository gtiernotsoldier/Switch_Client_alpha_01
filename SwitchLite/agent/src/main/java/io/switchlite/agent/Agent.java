package io.switchlite.agent;

import java.io.*;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Properties;

/**
 * Sandwich Architecture - Java Agent Entry Point
 * Layer 2: Class loading bytecode manipulation, mapping provider
 */
public class Agent {

    private static Instrumentation instrumentation;
    private static PrintStream logStream;
    private static boolean guiToggled = false;

    // ── File logger ──

    private static void log(String msg) {
        System.out.println(msg);
        if (logStream != null) logStream.println(msg);
        logStream.flush();
    }

    // ── Entry points ──

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
     */
    public static void bootstrap(String configDir) {
        try {
            logStream = new PrintStream(new FileOutputStream(
                new File(configDir, "switchlite-agent.log"), true), true);
        } catch (Exception e) { /* fallthrough */ }

        log("========== SwitchLite Agent JNI Bootstrap ==========");
        log("[Agent] java.io.tmpdir = " + System.getProperty("java.io.tmpdir"));
        log("[Agent] configDir = " + configDir);
        log("[Agent] Thread = " + Thread.currentThread().getName());
        log("[Agent] ClassLoader = " + Agent.class.getClassLoader().getClass().getName());

        String platform = "Unknown";
        String version = "Unknown";
        File configFile = new File(configDir, "switchlite-config.properties");
        log("[Agent] Config file path: " + configFile.getAbsolutePath() + " (exists=" + configFile.exists() + ")");

        if (configFile.exists()) {
            Properties props = new Properties();
            try (FileInputStream fis = new FileInputStream(configFile)) {
                props.load(fis);
                platform = props.getProperty("switchlite.platform", "Unknown");
                version = props.getProperty("switchlite.version", "Unknown");
                // Strip leading '=' if present (config parses version as "=1.8.9")
                if (version.startsWith("=")) version = version.substring(1);
                log("[Agent] Config loaded successfully");
            } catch (IOException e) {
                log("[Agent] Failed to read config: " + e.getMessage());
            }
        }

        log("[Agent] Platform: " + platform + " | Version: " + version);

        String mappingsDir = detectMappingsDir();
        log("[Agent] " + (mappingsDir == null ? "Using embedded mappings" : "Mappings dir: " + mappingsDir));

        try {
            MappingLoader.loadMappings(platform, version, mappingsDir);
        } catch (Exception e) {
            log("[Agent] Mapping load FAILED (non-fatal for verification): " + e.getMessage());
        }

        MappingContext.initialize();
        log("[Agent] MappingContext initialized");

        // Chat verification with runtime method discovery (SRG name resilient)
        sendChatVerification();

        // Start R key polling thread for GUI toggle
        startKeyPoll();

        log("[SwitchLite Agent] Ready — R key listener active");
    }

    // ── Chat verification ──

    private static void sendChatVerification() {
        try {
            Class<?> mcClass = Class.forName("net.minecraft.client.Minecraft");

            // --- Find getMinecraft() equivalent ---
            Method getMc = null;
            for (Method m : mcClass.getDeclaredMethods()) {
                if (m.getParameterCount() == 0 &&
                    m.getReturnType() == mcClass &&
                    java.lang.reflect.Modifier.isStatic(m.getModifiers())) {
                    getMc = m;
                    break;
                }
            }
            if (getMc == null) {
                log("[Chat] getMinecraft() not found, searching all methods...");
                for (Method m : mcClass.getMethods()) {
                    if (m.getParameterCount() == 0 && m.getReturnType() == mcClass) {
                        getMc = m;
                        break;
                    }
                }
            }
            if (getMc == null) { log("[Chat] No static Minecraft factory found"); return; }
            log("[Chat] Found alternative: " + getMc);

            Object mc = getMc.invoke(null);
            if (mc == null) { log("[Chat] MC instance is null"); return; }
            log("[Chat] MC instance: " + mc.getClass().getSimpleName());

            // --- Find thePlayer equivalent ---
            Field playerField = null;
            for (Field f : mcClass.getDeclaredFields()) {
                if (f.getType().getName().contains("EntityPlayer")) {
                    playerField = f;
                    break;
                }
            }
            if (playerField == null) {
                log("[Chat] thePlayer field not found, searching...");
                for (Field f : mcClass.getFields()) {
                    if (f.getType().getName().contains("EntityPlayer")) {
                        playerField = f;
                        break;
                    }
                }
            }
            if (playerField == null) { log("[Chat] No player field found in Minecraft"); return; }
            log("[Chat] Found player field: " + playerField);

            Object player = playerField.get(mc);
            if (player == null) { log("[Chat] Player is null"); return; }
            log("[Chat] Player: " + player.getClass().getSimpleName());

            // --- Find sendChatMessage / addChatMessage equivalent ---
            Method chatMethod = null;
            for (Method m : player.getClass().getMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (p.length == 1 && p[0] == String.class &&
                    m.getReturnType() == void.class &&
                    (m.getName().toLowerCase().contains("chat") ||
                     m.getName().toLowerCase().contains("message"))) {
                    chatMethod = m;
                    break;
                }
            }
            if (chatMethod == null) {
                log("[Chat] sendChatMessage not found on " + player.getClass().getSimpleName() + ", searching...");
                // Broad search: any method taking String and returning void
                for (Method m : player.getClass().getMethods()) {
                    Class<?>[] p = m.getParameterTypes();
                    if (p.length == 1 && p[0] == String.class && m.getReturnType() == void.class) {
                        chatMethod = m;
                        break;
                    }
                }
            }
            if (chatMethod != null) {
                chatMethod.invoke(player, "\u00a7a[SwitchLite] \u00a7fInjected! Press R for GUI");
                log("[Chat] Message sent via: " + chatMethod.getName());
            } else {
                log("[Chat] No chat method found — SRG name unknown");
            }
        } catch (Exception e) {
            log("[Chat] Verification failed: " + e.getMessage());
        }
    }

    // ── R key polling ──

    private static void startKeyPoll() {
        Thread poller = new Thread(() -> {
            log("[KeyPoll] Thread started, polling LWJGL2 Keyboard for R key");
            boolean wasPressed = false;
            while (true) {
                try {
                    boolean isPressed = org.lwjgl.input.Keyboard.isKeyDown(org.lwjgl.input.Keyboard.KEY_R);
                    if (isPressed && !wasPressed) {
                        guiToggled = !guiToggled;
                        log("[KeyPoll] R pressed — GUI toggled: " + (guiToggled ? "ON" : "OFF"));
                        // If GUI is on, send toggle confirmation
                        sendChatVerification();
                    }
                    wasPressed = isPressed;
                    Thread.sleep(50);
                } catch (Exception e) { break; }
            }
        }, "SwitchLite-KeyPoll");
        poller.setDaemon(true);
        poller.start();
    }

    // ── init (for agentmain/premain) ──

    private static void init(Instrumentation inst) {
        instrumentation = inst;

        String platform = "Unknown";
        String version = "Unknown";
        String configPath = detectConfigPath();

        if (configPath != null) {
            Properties props = new Properties();
            try (FileInputStream fis = new FileInputStream(configPath)) {
                props.load(fis);
                platform = props.getProperty("switchlite.platform", "Unknown");
                version = props.getProperty("switchlite.version", "Unknown");
                if (version.startsWith("=")) version = version.substring(1);
                System.out.println("[Agent] Config loaded from: " + configPath);
            } catch (IOException e) {
                System.err.println("[Agent] Failed to read config: " + e.getMessage());
            }
        } else {
            System.err.println("[Agent] Config file not found, using defaults");
        }

        System.out.println("[Agent] Platform: " + platform);
        System.out.println("[Agent] Version: " + version);

        String mappingsDir = detectMappingsDir();

        try {
            MappingLoader.loadMappings(platform, version, mappingsDir);
        } catch (Exception e) {
            System.err.println("[Agent] Failed to load mappings: " + e.getMessage());
            e.printStackTrace();
            return;
        }

        inst.addTransformer(new Transformer());
        System.out.println("[Agent] Transformer registered");

        MappingContext.initialize();
        System.out.println("[SwitchLite Agent] Ready");
    }

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

    private static String detectMappingsDir() {
        try {
            InputStream test = Agent.class.getClassLoader()
                .getResourceAsStream("mappings/forge/v1_8_9.json");
            if (test != null) {
                test.close();
                return null;
            }
        } catch (Exception e) { /* ignore */ }
        return "./mappings";
    }
}
