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

        // ── retransform-display path: hook LWJGL Display.update() via retransformClasses ──
        if ("retransform-display".equals(agentArgs)) {
            try {
                String agentJar = System.getProperty("java.io.tmpdir") + "/switchlite-agent.jar";
                java.io.File jarFile = new java.io.File(agentJar);
                if (!jarFile.exists()) jarFile = new java.io.File(agentJar.replace("/", "\\"));
                if (jarFile.exists()) {
                    inst.appendToBootstrapClassLoaderSearch(new java.util.jar.JarFile(jarFile));
                }
                Class<?> displayClass = Class.forName("org.lwjgl.opengl.Display");
                inst.addTransformer(new Transformer(), true);
                inst.retransformClasses(displayClass);
                log("[Agent] Display.update() hooked via attach + retransform");
            } catch (Exception e) {
                log("[Agent] Retransform failed: " + e.getMessage());
            }
            return;
        }

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

        // Start HUD tick thread: writes state via System.setProperty for RenderHook
        startHudTickThread();

        // Wait for player to enter world before sending welcome message
        // (bootstrap completes in ~400ms, but player may not be loaded yet)
        waitForPlayerThenWelcome();
        log("[SwitchLite Agent] Ready — R key listener active");
    }

    // ═══════════════════════════════════════════
    //  Wait for player, then send welcome message
    // ═══════════════════════════════════════════

    /**
     * Polls until thePlayer is non-null (player entered a world),
     * then sends the welcome messages. Runs on a daemon thread so it
     * doesn't block bootstrap completion.
     */
    private static void waitForPlayerThenWelcome() {
        Thread waitThread = new Thread(() -> {
            log("[Welcome] Waiting for player to enter world...");
            try {
                Class<?> mcClass = Class.forName("net.minecraft.client.Minecraft");
                int maxWait = 120; // 120 x 500ms = 60s max wait
                for (int i = 0; i < maxWait; i++) {
                    Object mc = null;
                    for (String name : MC_GET_MC) {
                        try {
                            java.lang.reflect.Method m = mcClass.getMethod(name);
                            mc = m.invoke(null);
                            if (mc != null) break;
                        } catch (Exception ignored) {}
                    }
                    if (mc != null) {
                        for (String name : MC_THE_PLAYER) {
                            try {
                                java.lang.reflect.Field f = mcClass.getField(name);
                                Object player = f.get(mc);
                                if (player != null) {
                                    log("[Welcome] Player detected! Sending welcome messages.");
                                    sendLocalMessage("Agent injected successfully!", GREEN);
                                    sendLocalMessage("Press R to toggle module status", GRAY);
                                    return; // done
                                }
                            } catch (Exception ignored) {}
                        }
                    }
                    Thread.sleep(500);
                }
                log("[Welcome] Timeout (60s) — player never joined. R key still works.");
            } catch (Exception e) {
                log("[Welcome] Error: " + e.getMessage());
            }
        }, "SwitchLite-WelcomeWait");
        waitThread.setDaemon(true);
        waitThread.start();
    }

    // ═══════════════════════════════════════════
    //  Right Shift Key Polling (LWJGL2 Keyboard)
    // ═══════════════════════════════════════════

    private static void startKeyPollThread() {
        keyPollThread = new Thread(() -> {
            log("[KeyPoll] Thread started, polling LWJGL2 Keyboard for Right Shift");
            try {
                Class<?> keyboardClass = Class.forName("org.lwjgl.input.Keyboard");
                java.lang.reflect.Method isKeyDown = keyboardClass.getMethod("isKeyDown", int.class);

                // LWJGL2 key code: Right Shift = 54
                boolean rWasDown = false;
                while (running) {
                    try {
                        boolean rDown = (Boolean) isKeyDown.invoke(null, 54);
                        if (rDown && !rWasDown) {
                            onRKeyPressed();
                        }
                        rWasDown = rDown;
                    } catch (Exception e) { /* polling before Display init */ }
                    Thread.sleep(50);
                }
                log("[KeyPoll] Thread stopped");
            } catch (ClassNotFoundException e) {
                log("[KeyPoll] LWJGL Keyboard class not found! MC not fully loaded yet.");
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
                        java.lang.reflect.Method isKeyDown =
                            Class.forName("org.lwjgl.input.Keyboard").getMethod("isKeyDown", int.class);
                        boolean rWasDown = false;
                        while (running) {
                            try {
                                boolean rDown = (Boolean) isKeyDown.invoke(null, 54);
                                if (rDown && !rWasDown) {
                                    onRKeyPressed();
                                }
                                rWasDown = rDown;
                            } catch (Exception ignored) {}
                            Thread.sleep(50);
                        }
                        started = true;
                    } catch (ClassNotFoundException e) {
                        Thread.sleep(2000);
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
        String status = guiVisible ? "ON" : "OFF";
        log("[KeyPoll] Right Shift pressed — GUI toggled: " + status);

        // Write state via System properties for RenderHook (cross-thread)
        System.setProperty("switchlite.guiOpen", String.valueOf(guiVisible));

        if (guiVisible) {
            sendLocalMessage("Modules: ACTIVE (alive at " + System.currentTimeMillis() + ")", GREEN);
        } else {
            sendLocalMessage("Modules: DISABLED", RED);
        }
    }

    // ═══════════════════════════════════════════
    //  HUD tick: write state via System.setProperty for RenderHook
    // ═══════════════════════════════════════════

    private static void startHudTickThread() {
        Thread hudThread = new Thread(() -> {
            while (running) {
                try {
                    // Build HUD text from enabled modules (via reflection since adapter:common may not be in classpath yet)
                    buildAndSetHudText();
                    System.setProperty("switchlite.guiOpen", String.valueOf(guiVisible));
                    Thread.sleep(100);
                } catch (Exception ignored) {}
            }
        }, "SwitchLite-HudTick");
        hudThread.setDaemon(true);
        hudThread.start();
    }

    private static void buildAndSetHudText() {
        StringBuilder sb = new StringBuilder("SwitchLite");
        try {
            // Try AgentBridge via reflection (in adapter:common, always in fat jar)
            Class<?> bridge = Class.forName("io.switchlite.adapter.common.AgentBridge");
            java.lang.reflect.Method getHudText = bridge.getMethod("getHudText");
            String text = (String) getHudText.invoke(null);
            if (text != null && !text.isEmpty()) sb = new StringBuilder(text);
        } catch (Exception e) {
            // AgentBridge not loaded yet — use fallback
        }
        System.setProperty("switchlite.hudText", sb.toString());
    }

    // ═══════════════════════════════════════════
    //  Chat message helper (reflection)
    // ═══════════════════════════════════════════

    // ═══════════════════════════════════════════
    //  Chat message — LOCAL only via addChatMessage(IChatComponent)
    //  Uses addChatMessage (NOT sendChatMessage) so messages are purely client-side.
    //  sendChatMessage goes to the server and gets filtered (Illegal characters, kick).
    //  addChatMessage + ChatComponentText = local HUD text, no server interaction.
    // ═══════════════════════════════════════════

    // Forge 1.8.9 SRG names — ACTUAL runtime names in Forge's deobfuscated jar.
    private static final String[] MC_GET_MC      = {"getMinecraft", "func_71410_x"};
    private static final String[] MC_THE_PLAYER   = {"thePlayer", "field_71439_g"};
    // ONLY addChatMessage — local-only, no server kick risk
    private static final String[] PLAYER_ADD_CHAT  = {"addChatMessage", "func_146235_e"};

    // EnumChatFormatting color codes for styled messages
    private static final char COLOR_CHAR = '\u00a7'; // section sign (MC color code prefix)
    private static final String GOLD   = COLOR_CHAR + "6";  // gold
    private static final String GREEN  = COLOR_CHAR + "a";  // green
    private static final String RED    = COLOR_CHAR + "c";  // red
    private static final String GRAY   = COLOR_CHAR + "7";  // gray
    private static final String WHITE  = COLOR_CHAR + "f";  // white
    private static final String BOLD   = COLOR_CHAR + "l";  // bold
    private static final String RESET  = COLOR_CHAR + "r";  // reset

    private static String mcGetInstance; // cache which name worked
    private static String mcPlayerField; // cache which name worked
    private static String mcAddChatMethod; // cache which name worked

    /**
     * Send a LOCAL chat message (client-side only).
     * Uses GuiIngame.getChatGUI().printChatMessage(IChatComponent) which
     * does NOT go through the server — no "Illegal characters" kick risk.
     *
     * Fallback path: EntityPlayer.addChatMessage(IChatComponent) via getDeclaredMethods
     * walking up the class hierarchy (method may be on AbstractClientPlayer, not EntityPlayerSP).
     *
     * @param text  the message text (plain, no color codes)
     * @param color one of: GREEN, GOLD, RED, GRAY, WHITE
     */
    private static void sendLocalMessage(String text, String color) {
        try {
            // --- Step 1: Find Minecraft instance via runtime scanning ---
            Class<?> mcClass = Class.forName("net.minecraft.client.Minecraft");
            Class<?> ichatCompClass = Class.forName("net.minecraft.util.IChatComponent");
            Class<?> chatCompClass = Class.forName("net.minecraft.util.ChatComponentText");

            // Step 1: Get Minecraft instance (cached after first success)
            Object mc = null;
            if (mcGetInstance != null) {
                try {
                    java.lang.reflect.Method m = mcClass.getMethod(mcGetInstance);
                    mc = m.invoke(null);
                } catch (Exception e) {
                    mcGetInstance = null;
                }
            }
            if (mc == null) {
                for (String name : MC_GET_MC) {
                    try {
                        java.lang.reflect.Method m = mcClass.getMethod(name);
                        mc = m.invoke(null);
                        if (mc != null) { mcGetInstance = name; log("[Chat] getMC via: " + name); break; }
                    } catch (Exception ignored) {}
                }
            }
            if (mc == null) {
                log("[Chat] MC instance is null — game not fully loaded");
                return;
            }

            // Step 2: Get thePlayer (cached)
            Object player = null;
            if (mcPlayerField != null) {
                try {
                    java.lang.reflect.Field f = mcClass.getField(mcPlayerField);
                    player = f.get(mc);
                } catch (Exception e) {
                    mcPlayerField = null;
                }
            }
            if (player == null) {
                for (String name : MC_THE_PLAYER) {
                    try {
                        java.lang.reflect.Field f = mcClass.getField(name);
                        player = f.get(mc);
                        if (player != null) { mcPlayerField = name; log("[Chat] player via: " + name); break; }
                    } catch (Exception ignored) {}
                }
            }
            if (player == null) {
                log("[Chat] Player is null — not in world yet");
                return;
            }

            // Step 3: Build IChatComponent with styled text
            String styledText = color + BOLD + "SwitchLite> " + RESET + color + text;
            Object chatComp = chatCompClass.getConstructor(String.class).newInstance(styledText);

            // ── Path A (preferred): GuiIngame → GuiNewChat → printChatMessage ──
            // This is the most direct way to add a message to the chat HUD.
            // ingameGUI field names: "ingameGUI" (MCP) / "field_71438_f" (SRG)
            boolean sent = false;
            String[] mcIngameGuiFields = {"ingameGUI", "field_71438_f"};
            for (String fieldName : mcIngameGuiFields) {
                try {
                    java.lang.reflect.Field f = mcClass.getField(fieldName);
                    Object ingameGUI = f.get(mc);
                    if (ingameGUI == null) continue;
                    // GuiNewChat from GuiIngame: getChatGUI() / field_146244_j
                    String[] getChatGuiNames = {"getChatGUI", "func_146244_j"};
                    Object chatGui = null;
                    for (String mname : getChatGuiNames) {
                        try {
                            java.lang.reflect.Method m = ingameGUI.getClass().getMethod(mname);
                            chatGui = m.invoke(ingameGUI);
                            if (chatGui != null) break;
                        } catch (Exception ignored) {}
                    }
                    if (chatGui == null) continue;
                    // GuiNewChat.printChatMessage(IChatComponent) / func_146227_a
                    String[] printMsgNames = {"printChatMessage", "func_146227_a",
                                              "printChatMessageWithOptionalDelete", "func_146237_a"};
                    for (String mname : printMsgNames) {
                        try {
                            java.lang.reflect.Method m = chatGui.getClass().getMethod(mname, ichatCompClass);
                            m.invoke(chatGui, chatComp);
                            log("[Chat] Local msg via GuiNewChat." + mname);
                            sent = true;
                            break;
                        } catch (Exception ignored) {}
                    }
                    if (sent) return;
                } catch (Exception ignored) {}
            }

            // ── Path B (fallback): walk inheritance chain for addChatMessage ──
            // getMethod only searches the concrete class; addChatMessage may be
            // declared on a superclass (AbstractClientPlayer or EntityPlayer).
            Class<?> clazz = player.getClass();
            while (clazz != null && clazz != Object.class) {
                for (java.lang.reflect.Method m : clazz.getDeclaredMethods()) {
                    if (m.getName().equals("addChatMessage") || m.getName().equals("func_146235_e")) {
                        try {
                            m.setAccessible(true);
                            m.invoke(player, chatComp);
                            log("[Chat] Local msg via " + clazz.getSimpleName() + "." + m.getName());
                            return;
                        } catch (Exception ignored) {}
                    }
                }
                clazz = clazz.getSuperclass();
            }

            log("[Chat] Local path failed, falling back to sendChatMessage...");
            // Fallback: sendChatMessage(String) — proven working, use plain ASCII
            Class<?> clz = player.getClass();
            while (clz != null) {
                try {
                    java.lang.reflect.Method m = clz.getDeclaredMethod("sendChatMessage", String.class);
                    m.setAccessible(true);
                    m.invoke(player, "SwitchLite Agent injected successfully");
                    log("[Chat] Sent via fallback sendChatMessage");
                    return;
                } catch (NoSuchMethodException e) {
                    clz = clz.getSuperclass();
                } catch (Exception e) { break; }
            }
            // Try SRG name func_71165_d
            try {
                java.lang.reflect.Method m = player.getClass().getMethod("func_71165_d", String.class);
                m.invoke(player, "SwitchLite Agent injected successfully");
                log("[Chat] Sent via SRG func_71165_d");
                return;
            } catch (Exception ignored) {}
            log("[Chat] Failed to send message — all paths exhausted");
        } catch (ClassNotFoundException e) {
            log("[Chat] MC class not found — wrong classloader or MC not loaded");
        } catch (Exception e) {
            log("[Chat] Error: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /** Convenience: send with default GOLD color. */
    private static void sendLocalMessage(String text) {
        sendLocalMessage(text, GOLD);
    }

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
