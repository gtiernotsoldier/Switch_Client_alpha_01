package io.switchlite.agent;

import java.io.*;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Properties;

/**
 * Sandwich Architecture - Java Agent Entry Point
 * Layer 2: Orchestration only — loading mappings, initializing modules, dispatching.
 *
 * What Agent DOES (its job):
 *   - Entry points (bootstrap/premain/agentmain)
 *   - Logging (stdout + file)
 *   - Load mappings + initialize MappingContext
 *   - Initialize adapter layer (AgentBridge, ForgeBootstrap)
 *   - Start tick thread → ForgeBootstrap.tick()
 *   - Start key poll thread → ForgeBootstrap.onKey()
 *   - Ask Transformer to install the Display.update() hook
 *   - Fallback: addScheduledTask → ForgeBootstrap.render() if Transformer fails
 *
 * What Agent DOES NOT do (not its job):
 *   - GL rendering → ForgeBootstrap.render()
 *   - Bytecode injection → Transformer + RenderHook
 *   - Getting Instrumentation → Transformer.install()
 *   - Self-attach → Transformer.install()
 *   - Drawing overlays → ForgeBootstrap.render()
 */
public class Agent {

    private static Instrumentation instrumentation;
    private static volatile boolean guiVisible = false;

    // LWJGL2 key code for Right Shift = 54
    private static final int KEY_RIGHT_SHIFT = 54;
    private static volatile boolean running = true;
    private static Thread keyPollThread = null;
    private static File logFile = null;
    private static PrintWriter logStream = null;

    // ═══════════════════════════════════════════
    //  Logging — dual output: stdout + file
    // ═══════════════════════════════════════════

    private static void initLogFile() {
        String tempDir = System.getProperty("java.io.tmpdir");
        logFile = new File(tempDir, "switchlite-agent.log");
        try {
            logStream = new PrintWriter(new FileWriter(logFile, true), true);
        } catch (Exception ignored) {}
    }

    static void log(String msg) {
        System.out.println(msg);
        if (logStream != null) {
            try {
                String timestamp = new SimpleDateFormat("HH:mm:ss.SSS").format(new Date());
                logStream.println("[" + timestamp + "] " + msg);
                logStream.flush();
            } catch (Exception ignored) {}
        }
    }

    // ═══════════════════════════════════════════
    //  Entry points
    // ═══════════════════════════════════════════

    public static void agentmain(String agentArgs, Instrumentation inst) {
        initLogFile();
        log("[SwitchLite Agent] Attached to running JVM (args=" + agentArgs + ")");
        instrumentation = inst;

        // Self-attach callback: Transformer asked us to retransform Display
        if ("retransform-display".equals(agentArgs)) {
            Transformer.handleRetransform(inst);
            return;
        }

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

        // Load mappings
        String mappingsDir = detectMappingsDir();
        try {
            MappingLoader.loadMappings(platform, version, mappingsDir);
            log("[Agent] Mappings loaded OK");
        } catch (Exception e) {
            log("[Agent] Mapping load FAILED (non-fatal for verification): " + e.getMessage());
        }

        MappingContext.initialize();
        log("[Agent] MappingContext initialized");

        // ========== Initialize adapter layer ==========
        try {
            Class<?> bridgeClass = Class.forName("io.switchlite.adapter.common.AgentBridge");
            Method initMethod = bridgeClass.getMethod("initModules");
            String result = (String) initMethod.invoke(null);
            log(result);
        } catch (ClassNotFoundException e) {
            log("[Agent] AgentBridge class not found — adapter:common not in agent.jar!");
        } catch (NoSuchMethodException e) {
            log("[Agent] AgentBridge.initModules() method not found");
        } catch (Exception e) {
            log("[Agent] AgentBridge.initModules() failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            Throwable cause = e.getCause();
            while (cause != null) {
                log("[Agent]   Caused by: " + cause.getClass().getSimpleName() + ": " + cause.getMessage());
                for (StackTraceElement ste : cause.getStackTrace()) {
                    if (ste.getClassName().startsWith("io.switchlite")) {
                        log("[Agent]     at " + ste.toString());
                    }
                }
                cause = cause.getCause();
            }
        }

        // Initialize Forge adapter (pure reflection — no ForgeGradle needed)
        if ("Forge".equals(platform)) {
            try {
                Class<?> bootstrapClass = Class.forName("io.switchlite.adapter.forge.v1_8_9.ForgeBootstrap");
                Object fbInstance = bootstrapClass.getField("INSTANCE").get(null);
                Method initMethod = bootstrapClass.getMethod("init");
                initMethod.invoke(fbInstance);
                log("[Agent] ForgeBootstrap.init() complete (pure reflection mode)");

                // Cache method refs for tick/render/onKey dispatch
                cacheForgeBootstrapMethods(bootstrapClass, fbInstance);

                // Disable old EventBridge.onTick(null,null) dispatch — ForgeBootstrap.tick() handles it now
            } catch (ClassNotFoundException e) {
                log("[Agent] ForgeBootstrap not in classpath — HUD via Display.update() hook");
            } catch (Exception e) {
                log("[Agent] ForgeBootstrap init failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }

        // ========== Ask Transformer to install Display.update() hook ==========
        // This is Javassist's job, not Agent's. We just call install().
        boolean hookInstalled = Transformer.install(instrumentation);
        if (hookInstalled) {
            log("[Agent] Render path: Transformer + RenderHook → ForgeBootstrap.render() (every frame, stealthy)");
        } else {
            log("[Agent] Render path: addScheduledTask → ForgeBootstrap.render() (20Hz fallback, less stealthy)");
        }

        // Start Right Shift key polling thread
        startKeyPollThread();

        // Start HUD tick thread — dispatches tick events + render fallback
        startHudTickThread();

        // Wait for player to enter world before sending welcome message
        waitForPlayerThenWelcome();
        log("[SwitchLite Agent] Ready — Right Shift key listener active");
    }

    // ═══════════════════════════════════════════
    //  ForgeBootstrap method cache
    // ═══════════════════════════════════════════

    // Cached reflection for ForgeBootstrap.tick()/onKey()/render() calls
    private static Object forgeBootstrapInstance = null;
    private static Method forgeBootstrapTick = null;
    private static Method forgeBootstrapOnKey = null;
    private static Method forgeBootstrapRender = null;
    private static Method forgeBootstrapOnDisconnect = null;
    private static boolean forgeBootstrapAvailable = false;

    private static void cacheForgeBootstrapMethods(Class<?> fbClass, Object fbInstance) {
        try {
            forgeBootstrapInstance = fbInstance;
            forgeBootstrapTick = fbClass.getMethod("tick");
            forgeBootstrapOnKey = fbClass.getMethod("onKey", int.class, boolean.class);
            forgeBootstrapRender = fbClass.getMethod("render");
            forgeBootstrapOnDisconnect = fbClass.getMethod("onDisconnect");
            forgeBootstrapAvailable = true;
            log("[Agent] ForgeBootstrap methods cached including render() (pure reflection mode)");
        } catch (Exception e) {
            log("[Agent] ForgeBootstrap method cache failed: " + e.getMessage());
        }
    }

    // ═══════════════════════════════════════════
    //  HUD Tick Thread — tick dispatch + render fallback
    // ═══════════════════════════════════════════

    private static volatile String lastHudText = "";
    private static Thread hudTickThread = null;

    /**
     * Runs at 20Hz. Dispatches tick events to ForgeBootstrap.tick(),
     * and if the Transformer hook is NOT installed, uses addScheduledTask
     * to schedule ForgeBootstrap.render() on the MC main thread.
     *
     * If Transformer IS installed, RenderHook.onFrame() handles rendering
     * automatically every frame — no need for this thread to do rendering.
     */
    private static void startHudTickThread() {
        hudTickThread = new Thread(() -> {
            log("[HudTick] Thread started (20Hz tick + render fallback)");
            try {
                Class<?> ebClass = Class.forName("io.switchlite.adapter.common.api.EventBridge");
                Method getHudText = ebClass.getMethod("getHudTextLine");
                Method isGuiOpen = ebClass.getMethod("getIsGuiOpen");

                while (running) {
                    try {
                        // Dispatch tick to ForgeBootstrap (extracts player state, modules process)
                        if (forgeBootstrapAvailable && forgeBootstrapTick != null && forgeBootstrapInstance != null) {
                            forgeBootstrapTick.invoke(forgeBootstrapInstance);
                        }

                        // Read HUD text for chat-based fallback
                        String hudText = (String) getHudText.invoke(null);
                        boolean guiOpen = (Boolean) isGuiOpen.invoke(null);

                        // Push state via System.getProperties (cross-classloader safe, used by RenderHook)
                        System.setProperty("switchlite.guiOpen", String.valueOf(guiOpen));
                        System.setProperty("switchlite.hudText", hudText != null ? hudText : "");

                        // Render fallback: if Transformer hook is NOT installed, use addScheduledTask
                        if (!Transformer.isInstalled()) {
                            dispatchRenderFallback();
                        }

                        // Chat-based HUD fallback (action bar)
                        if (!guiOpen && hudText != null && !hudText.isEmpty() && !hudText.equals(lastHudText)) {
                            lastHudText = hudText;
                            sendActionBarMessage(hudText);
                        }

                        if (guiOpen && !lastHudText.contains("[GUI]")) {
                            lastHudText = "[GUI] " + hudText;
                            sendActionBarMessage("\u00a7a[SwitchLite GUI Open] \u00a77RShift=close");
                        }
                    } catch (Exception ignored) {}
                    Thread.sleep(50); // 20 Hz
                }
            } catch (ClassNotFoundException e) {
                log("[HudTick] EventBridge not found — HUD disabled");
            } catch (Exception e) {
                log("[HudTick] Error: " + e.getMessage());
            }
        }, "SwitchLite-HudTick");
        hudTickThread.setDaemon(true);
        hudTickThread.start();
    }

    /**
     * Fallback render dispatch: schedule ForgeBootstrap.render() on the MC main thread
     * via addScheduledTask. This is the ONLY thread that holds the OpenGL context.
     *
     * This path is only used when Transformer.install() failed (no Instrumentation).
     * It's less stealthy than the bytecode injection path, but it works.
     */
    private static void dispatchRenderFallback() {
        try {
            if (!forgeBootstrapAvailable || forgeBootstrapRender == null || forgeBootstrapInstance == null) return;

            Class<?> mcClass = Class.forName("net.minecraft.client.Minecraft");
            java.lang.reflect.Method mcFactory = null;
            for (String name : MC_GET_MC) {
                try { mcFactory = mcClass.getMethod(name); break; }
                catch (NoSuchMethodException ignored) {}
            }
            if (mcFactory == null) return;

            Object mc = mcFactory.invoke(null);
            if (mc == null) return;

            // Schedule ForgeBootstrap.render() on MC main thread (has GL context)
            java.lang.reflect.Method addTask = null;
            for (String name : new String[]{"addScheduledTask", "func_152343_a"}) {
                try { addTask = mcClass.getMethod(name, Runnable.class); break; }
                catch (NoSuchMethodException ignored) {}
            }
            if (addTask == null) return;

            final Object fbInst = forgeBootstrapInstance;
            final Method fbRender = forgeBootstrapRender;
            addTask.invoke(mc, (Runnable) () -> {
                try {
                    fbRender.invoke(fbInst);
                } catch (Exception ignored) {}
            });
        } catch (Exception ignored) {}
    }

    // ═══════════════════════════════════════════
    //  Key Polling (LWJGL2 Keyboard)
    // ═══════════════════════════════════════════

    private static void startKeyPollThread() {
        keyPollThread = new Thread(() -> {
            log("[KeyPoll] Thread started, polling LWJGL2 Keyboard");
            try {
                Class<?> keyboardClass = Class.forName("org.lwjgl.input.Keyboard");
                Method isKeyDown = keyboardClass.getMethod("isKeyDown", int.class);
                Method getEventKey = keyboardClass.getMethod("getEventKey");
                Method getEventKeyState = keyboardClass.getMethod("getEventKeyState");
                Method isNext = keyboardClass.getMethod("next");

                boolean keyWasDown = false;
                while (running) {
                    try {
                        // Poll all LWJGL2 key events and dispatch to ForgeBootstrap
                        boolean nextResult = (Boolean) isNext.invoke(null);
                        if (nextResult) {
                            int lwjglCode = (Integer) getEventKey.invoke(null);
                            boolean pressed = (Boolean) getEventKeyState.invoke(null);
                            if (lwjglCode != 0 && forgeBootstrapAvailable && forgeBootstrapOnKey != null && forgeBootstrapInstance != null) {
                                forgeBootstrapOnKey.invoke(forgeBootstrapInstance, lwjglCode, pressed);
                            }
                        }

                        // Also keep the old Right Shift toggle for backward compat
                        boolean keyDown = (Boolean) isKeyDown.invoke(null, KEY_RIGHT_SHIFT);
                        if (keyDown && !keyWasDown) {
                            onToggleKeyPressed();
                        }
                        keyWasDown = keyDown;
                    } catch (Exception e) {
                        // Silently ignore polling errors (e.g., before Display is created)
                    }
                    Thread.sleep(50); // 20 Hz poll rate
                }
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
                        boolean keyWasDown = false;
                        while (running) {
                            try {
                                boolean keyDown = (Boolean) isKeyDown.invoke(null, KEY_RIGHT_SHIFT);
                                if (keyDown && !keyWasDown) {
                                    onToggleKeyPressed();
                                }
                                keyWasDown = keyDown;
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

    private static void onToggleKeyPressed() {
        guiVisible = !guiVisible;
        String status = guiVisible ? "ON" : "OFF";
        log("[KeyPoll] Right Shift pressed — GUI toggled: " + status);

        System.setProperty("switchlite.guiOpen", String.valueOf(guiVisible));
        System.setProperty("switchlite.hudText", lastHudText != null ? lastHudText : "");

        // Dispatch to EventBridge so module-layer keyListeners (ClickGUI) receive it
        try {
            Class<?> ebClass = Class.forName("io.switchlite.adapter.common.api.EventBridge");
            Method onKeyMethod = ebClass.getMethod("onKey", int.class, boolean.class);
            onKeyMethod.invoke(null, 344, true); // GLFW RIGHT_SHIFT, pressed
        } catch (ClassNotFoundException e) {
            // EventBridge not available — adapter:common not loaded
        } catch (Exception ignored) {}

        if (guiVisible) {
            sendLocalMessage("Modules: ACTIVE (alive at " + System.currentTimeMillis() + ")", GREEN);
        } else {
            sendLocalMessage("Modules: DISABLED", RED);
        }
    }

    // ═══════════════════════════════════════════
    //  Wait for player, then send welcome message
    // ═══════════════════════════════════════════

    private static void waitForPlayerThenWelcome() {
        Thread waitThread = new Thread(() -> {
            log("[Welcome] Waiting for player to enter world...");
            try {
                Class<?> mcClass = Class.forName("net.minecraft.client.Minecraft");
                int maxWait = 120;
                for (int i = 0; i < maxWait; i++) {
                    Object mc = null;
                    for (String name : MC_GET_MC) {
                        try {
                            Method m = mcClass.getMethod(name);
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
                                    sendLocalMessage("Press Right Shift to toggle module status", GRAY);
                                    return;
                                }
                            } catch (Exception ignored) {}
                        }
                    }
                    Thread.sleep(500);
                }
                log("[Welcome] Timeout (60s) — player never joined. Right Shift still works.");
            } catch (Exception e) {
                log("[Welcome] Error: " + e.getMessage());
            }
        }, "SwitchLite-WelcomeWait");
        waitThread.setDaemon(true);
        waitThread.start();
    }

    // ═══════════════════════════════════════════
    //  Chat message — LOCAL only via addChatMessage(IChatComponent)
    // ═══════════════════════════════════════════

    // Forge 1.8.9 SRG names
    private static final String[] MC_GET_MC      = {"getMinecraft", "func_71410_x"};
    private static final String[] MC_THE_PLAYER   = {"thePlayer", "field_71439_g"};

    // Color codes
    private static final char COLOR_CHAR = '\u00a7';
    private static final String GOLD   = COLOR_CHAR + "6";
    private static final String GREEN  = COLOR_CHAR + "a";
    private static final String RED    = COLOR_CHAR + "c";
    private static final String GRAY   = COLOR_CHAR + "7";
    private static final String WHITE  = COLOR_CHAR + "f";
    private static final String BOLD   = COLOR_CHAR + "l";
    private static final String RESET  = COLOR_CHAR + "r";

    private static String mcGetInstance;
    private static String mcPlayerField;

    /**
     * Send a LOCAL chat message (client-side only).
     * Uses GuiIngame.getChatGUI().printChatMessage(IChatComponent).
     */
    private static void sendLocalMessage(String text, String color) {
        try {
            Class<?> mcClass = Class.forName("net.minecraft.client.Minecraft");
            Class<?> ichatCompClass = Class.forName("net.minecraft.util.IChatComponent");
            Class<?> chatCompClass = Class.forName("net.minecraft.util.ChatComponentText");

            Object mc = null;
            if (mcGetInstance != null) {
                try {
                    mc = mcClass.getMethod(mcGetInstance).invoke(null);
                } catch (Exception e) { mcGetInstance = null; }
            }
            if (mc == null) {
                for (String name : MC_GET_MC) {
                    try {
                        Method m = mcClass.getMethod(name);
                        mc = m.invoke(null);
                        if (mc != null) { mcGetInstance = name; break; }
                    } catch (Exception ignored) {}
                }
            }
            if (mc == null) return;

            Object player = null;
            if (mcPlayerField != null) {
                try {
                    player = mcClass.getField(mcPlayerField).get(mc);
                } catch (Exception e) { mcPlayerField = null; }
            }
            if (player == null) {
                for (String name : MC_THE_PLAYER) {
                    try {
                        java.lang.reflect.Field f = mcClass.getField(name);
                        player = f.get(mc);
                        if (player != null) { mcPlayerField = name; break; }
                    } catch (Exception ignored) {}
                }
            }
            if (player == null) return;

            String styledText = color + BOLD + "SwitchLite> " + RESET + color + text;
            Object chatComp = chatCompClass.getConstructor(String.class).newInstance(styledText);

            boolean sent = false;
            String[] mcIngameGuiFields = {"ingameGUI", "field_71438_f"};
            for (String fieldName : mcIngameGuiFields) {
                try {
                    java.lang.reflect.Field f = mcClass.getField(fieldName);
                    Object ingameGUI = f.get(mc);
                    if (ingameGUI == null) continue;
                    String[] getChatGuiNames = {"getChatGUI", "func_146244_j"};
                    Object chatGui = null;
                    for (String mname : getChatGuiNames) {
                        try {
                            chatGui = ingameGUI.getClass().getMethod(mname).invoke(ingameGUI);
                            if (chatGui != null) break;
                        } catch (Exception ignored) {}
                    }
                    if (chatGui == null) continue;
                    String[] printMsgNames = {"printChatMessage", "func_146227_a",
                                              "printChatMessageWithOptionalDelete", "func_146237_a"};
                    for (String mname : printMsgNames) {
                        try {
                            Method m = chatGui.getClass().getMethod(mname, ichatCompClass);
                            m.invoke(chatGui, chatComp);
                            sent = true;
                            break;
                        } catch (Exception ignored) {}
                    }
                    if (sent) return;
                } catch (Exception ignored) {}
            }

            // Fallback: walk inheritance chain for addChatMessage
            Class<?> clazz = player.getClass();
            while (clazz != null && clazz != Object.class) {
                for (Method m : clazz.getDeclaredMethods()) {
                    if (m.getName().equals("addChatMessage") || m.getName().equals("func_146235_e")) {
                        try {
                            m.setAccessible(true);
                            m.invoke(player, chatComp);
                            return;
                        } catch (Exception ignored) {}
                    }
                }
                clazz = clazz.getSuperclass();
            }
        } catch (Exception ignored) {}
    }

    private static void sendLocalMessage(String text) {
        sendLocalMessage(text, GOLD);
    }

    private static void sendActionBarMessage(String text) {
        try {
            Class<?> mcClass = Class.forName("net.minecraft.client.Minecraft");
            Class<?> ichatCompClass = Class.forName("net.minecraft.util.IChatComponent");
            Class<?> chatCompClass = Class.forName("net.minecraft.util.ChatComponentText");

            Object mc = null;
            if (mcGetInstance != null) {
                try {
                    mc = mcClass.getMethod(mcGetInstance).invoke(null);
                } catch (Exception e) { mcGetInstance = null; }
            }
            if (mc == null) return;

            Object chatComp = chatCompClass.getConstructor(String.class).newInstance(text);

            String[] ingameGuiFields = {"ingameGUI", "field_71438_f"};
            for (String fn : ingameGuiFields) {
                try {
                    java.lang.reflect.Field f = mcClass.getField(fn);
                    Object ingameGUI = f.get(mc);
                    if (ingameGUI == null) continue;
                    String[] displayNames = {"displayActionBar", "func_146237_a",
                                              "setOverlayMessage", "func_110326_a"};
                    for (String mn : displayNames) {
                        try {
                            Method m = ingameGUI.getClass().getMethod(mn, ichatCompClass);
                            m.invoke(ingameGUI, chatComp);
                            return;
                        } catch (Exception ignored) {}
                    }
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
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
