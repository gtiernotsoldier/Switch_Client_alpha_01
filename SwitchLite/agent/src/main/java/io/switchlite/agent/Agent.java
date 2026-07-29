package io.switchlite.agent;

import java.io.*;
import java.lang.instrument.Instrumentation;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Properties;

/**
 * Sandwich Architecture - Java Agent Entry Point
 * Layer 2: Class loading bytecode manipulation, mapping provider
 *
 * Verification mode: after bootstrap, polls R key (LWJGL2) and sends chat messages.
 * All diagnostics also write to %TEMP%\switchlite-agent.log for reliable output.
 */
public class Agent {

    private static Instrumentation instrumentation;
    private static volatile boolean guiVisible = false;
    private static volatile boolean running = true;
    private static Thread keyPollThread = null;
    private static File logFile = null;

    // ═══════════════════════════════════════════
    //  Logging — dual output: stdout + file
    // ═══════════════════════════════════════════

    private static void initLogFile() {
        String tempDir = System.getProperty("java.io.tmpdir");
        logFile = new File(tempDir, "switchlite-agent.log");
    }

    private static void log(String msg) {
        // Always stdout (may or may not reach latest.log)
        System.out.println(msg);
        // Also write to file — guaranteed visible
        if (logFile != null) {
            try {
                String timestamp = new SimpleDateFormat("HH:mm:ss.SSS").format(new Date());
                String line = "[" + timestamp + "] " + msg + "\n";
                FileWriter fw = new FileWriter(logFile, true);
                fw.write(line);
                fw.close();
            } catch (Exception ignored) {}
        }
    }

    // ═══════════════════════════════════════════
    //  Entry points
    // ═══════════════════════════════════════════

    public static void agentmain(String agentArgs, Instrumentation inst) {
        initLogFile();
        log("[SwitchLite Agent] Attached to running JVM");
        init(inst);
    }

    public static void premain(String agentArgs, Instrumentation inst) {
        initLogFile();
        log("[SwitchLite Agent] Loaded at JVM startup");
        init(inst);
    }

    /**
     * Bootstrap entry for DLL injection + JNI (no Instrumentation).
     * Called by payload.dll via JNI CallStaticVoidMethod.
     */
    public static void bootstrap(String configDir) {
        initLogFile();
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
                log("[Agent] Config loaded successfully");
            } catch (IOException e) {
                log("[Agent] Failed to read config: " + e.getMessage());
            }
        }

        log("[Agent] Platform: " + platform + " | Version: " + version);

        // Try to load mappings — don't abort on failure in verification mode
        String mappingsDir = detectMappingsDir();
        try {
            MappingLoader.loadMappings(platform, version, mappingsDir);
            log("[Agent] Mappings loaded OK");
        } catch (Exception e) {
            log("[Agent] Mapping load FAILED (non-fatal for verification): " + e.getMessage());
            // Don't return — continue to verification
        }

        MappingContext.initialize();
        log("[Agent] MappingContext initialized");

        // Start R key polling thread for persistent verification
        startKeyPollThread();

        // Try immediate chat verification (may fail if not in world yet)
        sendChatMessage("\u00a7a[SwitchLite] \u00a7fAgent injected! Press R to toggle GUI (log: %TEMP%\\switchlite-agent.log)");
        log("[SwitchLite Agent] Ready — R key listener active");
    }

    // ═══════════════════════════════════════════
    //  R Key Polling (LWJGL2 Keyboard)
    // ═══════════════════════════════════════════

    private static void startKeyPollThread() {
        keyPollThread = new Thread(() -> {
            log("[KeyPoll] Thread started, polling LWJGL2 Keyboard for R key");
            try {
                // Access LWJGL2 Keyboard via reflection (agent layer, no direct MC dependency)
                Class<?> keyboardClass = Class.forName("org.lwjgl.input.Keyboard");
                java.lang.reflect.Method isKeyDown = keyboardClass.getMethod("isKeyDown", int.class);

                // LWJGL2 key code: R = 19
                boolean rWasDown = false;
                while (running) {
                    try {
                        boolean rDown = (Boolean) isKeyDown.invoke(null, 19);
                        if (rDown && !rWasDown) {
                            onRKeyPressed();
                        }
                        rWasDown = rDown;
                    } catch (Exception e) {
                        // Silently ignore polling errors (e.g., before Display is created)
                    }
                    Thread.sleep(50); // 20 Hz poll rate
                }
                log("[KeyPoll] Thread stopped");
            } catch (ClassNotFoundException e) {
                log("[KeyPoll] LWJGL Keyboard class not found! MC not fully loaded yet.");
                // Retry in a loop until LWJGL is available
                retryKeyPoll();
            } catch (Exception e) {
                log("[KeyPoll] Fatal error: " + e.getMessage());
            }
        }, "SwitchLite-KeyPoll");
        keyPollThread.setDaemon(true);
        keyPollThread.start();
    }

    private static void retryKeyPoll() {
        log("[KeyPoll] Will retry every 2s until LWJGL Keyboard is available...");
        Thread retryThread = new Thread(() -> {
            try {
                boolean started = false;
                while (running && !started) {
                    try {
                        Class.forName("org.lwjgl.input.Keyboard");
                        log("[KeyPoll] LWJGL Keyboard found! Starting poll...");
                        // Now start the actual poll
                        java.lang.reflect.Method isKeyDown =
                            Class.forName("org.lwjgl.input.Keyboard").getMethod("isKeyDown", int.class);
                        boolean rWasDown = false;
                        while (running) {
                            try {
                                boolean rDown = (Boolean) isKeyDown.invoke(null, 19);
                                if (rDown && !rWasDown) {
                                    onRKeyPressed();
                                }
                                rWasDown = rDown;
                            } catch (Exception ignored) {}
                            Thread.sleep(50);
                        }
                        started = true;
                    } catch (ClassNotFoundException e) {
                        Thread.sleep(2000); // Wait and retry
                    }
                }
            } catch (Exception e) {
                log("[KeyPoll] Retry loop error: " + e.getMessage());
            }
        }, "SwitchLite-KeyPollRetry");
        retryThread.setDaemon(true);
        retryThread.start();
    }

    private static void onRKeyPressed() {
        guiVisible = !guiVisible;
        String status = guiVisible ? "\u00a7aON" : "\u00a7cOFF";
        log("[KeyPoll] R pressed — GUI toggled: " + (guiVisible ? "ON" : "OFF"));

        if (guiVisible) {
            sendChatMessage("\u00a7a[SwitchLite] \u00a7fGUI: " + status + " \u00a77| Agent alive! Tick=" + System.currentTimeMillis());
        } else {
            sendChatMessage("\u00a7a[SwitchLite] \u00a7fGUI: " + status);
        }
    }

    // ═══════════════════════════════════════════
    //  Chat message helper (reflection)
    // ═══════════════════════════════════════════

    // Forge 1.8.9 SRG names — these are the ACTUAL runtime names in Forge's deobfuscated jar.
    // MCP mapped names (getMinecraft, thePlayer, sendChatMessage) do NOT exist at runtime.
    private static final String[] MC_GET_MC   = {"getMinecraft", "func_71410_x"};
    private static final String[] MC_THE_PLAYER = {"thePlayer", "field_71439_g"};
    private static final String[] PLAYER_SEND_CHAT = {"sendChatMessage", "func_71165_d", "addChatMessage", "func_146235_e"};

    private static void sendChatMessage(String text) {
        try {
            Class<?> mcClass = Class.forName("net.minecraft.client.Minecraft");

            // Step 1: Get Minecraft instance
            Object mc = null;
            for (String name : MC_GET_MC) {
                try {
                    java.lang.reflect.Method m = mcClass.getMethod(name);
                    mc = m.invoke(null);
                    if (mc != null) { log("[Chat] getMC via: " + name); break; }
                } catch (Exception ignored) {}
            }
            if (mc == null) {
                log("[Chat] MC instance is null — game not fully loaded");
                return;
            }

            // Step 2: Get thePlayer
            Object player = null;
            for (String name : MC_THE_PLAYER) {
                try {
                    java.lang.reflect.Field f = mcClass.getField(name);
                    player = f.get(mc);
                    if (player != null) { log("[Chat] player via: " + name); break; }
                } catch (Exception ignored) {}
            }
            if (player == null) {
                log("[Chat] Player is null — not in world yet");
                return;
            }

            // Step 3: Send chat message
            boolean sent = false;
            for (String name : PLAYER_SEND_CHAT) {
                try {
                    java.lang.reflect.Method m = player.getClass().getMethod(name, String.class);
                    m.invoke(player, text);
                    log("[Chat] Sent via: " + name + " → " + text);
                    sent = true;
                    break;
                } catch (Exception ignored) {}
            }
            if (!sent) {
                // Last resort: try EntityPlayerSP.sendChatMessage(IChatComponent) for newer mappings
                try {
                    Class<?> ichatComp = Class.forName("net.minecraft.util.IChatComponent");
                    Class<?> chatComp = Class.forName("net.minecraft.util.ChatComponentText");
                    Object comp = chatComp.getConstructor(String.class).newInstance(text);
                    for (String name : PLAYER_SEND_CHAT) {
                        try {
                            java.lang.reflect.Method m = player.getClass().getMethod(name, ichatComp);
                            m.invoke(player, comp);
                            log("[Chat] Sent via IChatComponent: " + name);
                            sent = true;
                            break;
                        } catch (Exception ignored) {}
                    }
                } catch (Exception ignored) {}
            }
            if (!sent) {
                log("[Chat] Failed to send chat — no compatible method found");
            }
        } catch (ClassNotFoundException e) {
            log("[Chat] MC class not found — wrong classloader or MC not loaded");
        } catch (Exception e) {
            log("[Chat] Error: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }
    private static boolean sendChatDumped = false;

    // ═══════════════════════════════════════════
    //  Original init (Instrumentation path)
    // ═══════════════════════════════════════════

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
                log("[Agent] Config loaded from: " + configPath);
            } catch (IOException e) {
                log("[Agent] Failed to read config: " + e.getMessage());
            }
        } else {
            log("[Agent] Config file not found, using defaults");
        }

        log("[Agent] Platform: " + platform);
        log("[Agent] Version: " + version);

        String mappingsDir = detectMappingsDir();

        try {
            MappingLoader.loadMappings(platform, version, mappingsDir);
        } catch (Exception e) {
            log("[Agent] Failed to load mappings: " + e.getMessage());
            e.printStackTrace();
            return;
        }

        inst.addTransformer(new Transformer());
        log("[Agent] Transformer registered");

        MappingContext.initialize();
        log("[SwitchLite Agent] Ready");
    }

    // ═══════════════════════════════════════════
    //  Utilities
    // ═══════════════════════════════════════════

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
                log("[Agent] Using embedded mappings");
                return null;
            }
        } catch (Exception e) { /* ignore */ }
        return "./mappings";
    }
}
