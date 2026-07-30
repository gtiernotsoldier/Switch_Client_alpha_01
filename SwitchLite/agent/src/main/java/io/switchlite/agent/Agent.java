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
 * Verification mode: after bootstrap, polls Right Shift key (LWJGL2) and sends chat messages.
 * This is a bootstrap-only verification mechanism. Once the adapter layer loads,
 * the ClickGUI module takes over key handling via EventBridge.
 * All diagnostics also write to %TEMP%\switchlite-agent.log for reliable output.
 */
public class Agent {

    private static Instrumentation instrumentation;
    private static volatile boolean guiVisible = false;

    // LWJGL2 key code for Right Shift = 54
    private static final int KEY_RIGHT_SHIFT = 54;
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

        // ========== Start adapter layer ==========
        // DLL injection runs AFTER Forge @Mod lifecycle is complete.
        // ForgeMod.onInit() will never fire, so we must initialize modules
        // ourselves. AgentBridge lives in adapter:common (always in agent fat jar).
        try {
            Class<?> bridgeClass = Class.forName("io.switchlite.adapter.common.AgentBridge");
            java.lang.reflect.Method initMethod = bridgeClass.getMethod("initModules");
            String result = (String) initMethod.invoke(null);
            log(result);
        } catch (ClassNotFoundException e) {
            log("[Agent] AgentBridge class not found — adapter:common not in agent.jar!");
        } catch (NoSuchMethodException e) {
            log("[Agent] AgentBridge.initModules() method not found");
        } catch (Exception e) {
            log("[Agent] AgentBridge.initModules() failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            Throwable cause = e.getCause();
            if (cause != null) {
                log("[Agent]   Caused by: " + cause.getClass().getSimpleName() + ": " + cause.getMessage());
            }
            // Don't print full stack trace — it may recurse. Continue boot.
        }

        // Initialize Forge adapter (pure reflection — no ForgeGradle needed at compile time).
        // ForgeBootstrap registers modules, wires EventBridge, injects packet interceptor.
        if ("Forge".equals(platform)) {
            try {
                Class<?> bootstrapClass = Class.forName("io.switchlite.adapter.forge.v1_8_9.ForgeBootstrap");
                java.lang.reflect.Method initMethod = bootstrapClass.getMethod("init");
                initMethod.invoke(null);
                log("[Agent] ForgeBootstrap.init() complete (pure reflection mode)");

                // Cache method refs for tick/render dispatch
                cacheForgeBootstrapMethods();

                // Cache mc.addScheduledTask for render thread dispatch
                try {
                    Class<?> mcClass = Class.forName("net.minecraft.client.Minecraft");
                    mcAddScheduledTask = mcClass.getMethod("addScheduledTask", Runnable.class);
                } catch (Exception e) {
                    log("[Agent] addScheduledTask not found — render will use fallback");
                }

                // Disable old EventBridge.onTick(null,null) dispatch — ForgeBootstrap.tick() handles it now
                // The HUD thread still reads hudText for action bar fallback
            } catch (ClassNotFoundException e) {
                log("[Agent] ForgeBootstrap not in classpath — using Javassist + inline renderer");
                // Try Javassist render hook first (renders in render phase — correct)
                hookRenderViaJavassist();
                // Also init inline renderer as fallback diagnostics
                initInlineRenderer();
            } catch (Exception e) {
                log("[Agent] ForgeBootstrap init failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }

        // Start Right Shift key polling thread for bootstrap verification
        startKeyPollThread();

        // Start HUD tick thread — dispatches tick events to EventBridge
        // so HUD module can build the enabled-modules list.
        // Without Forge adapter, HUD renders via chat messages (fallback).
        startHudTickThread();

        // Wait for player to enter world before sending welcome message
        // (bootstrap completes in ~400ms, but player may not be loaded yet)
        waitForPlayerThenWelcome();
        log("[SwitchLite Agent] Ready — Right Shift key listener active");
    }

    // ═══════════════════════════════════════════
    //  HUD Tick Thread — feeds EventBridge.onTick()
    // ═══════════════════════════════════════════

    private static volatile String lastHudText = "";
    private static Thread hudTickThread = null;

    /**
     * Runs at 2 Hz (every 500ms). Dispatches tick events to EventBridge
     * so the HUD module can rebuild its enabled-modules list.
     *
     * Also does a chat-based HUD fallback: if EventBridge.hudTextLine changed
     * and no Forge render hook is active, sends the HUD as a chat action bar message.
     */
    private static void startHudTickThread() {
        hudTickThread = new Thread(() -> {
            log("[HudTick] Thread started (20Hz tick + render dispatch)");
            try {
                Class<?> ebClass = Class.forName("io.switchlite.adapter.common.api.EventBridge");
                java.lang.reflect.Method onTickMethod = null;
                java.lang.reflect.Method getHudText = ebClass.getMethod("getHudTextLine");
                java.lang.reflect.Method isGuiOpen = ebClass.getMethod("getIsGuiOpen");

                while (running) {
                    try {
                        // Dispatch tick to ForgeBootstrap (extracts player state, modules process)
                        if (forgeBootstrapAvailable && forgeBootstrapTick != null) {
                            forgeBootstrapTick.invoke(null);
                        }

                        // Schedule render on MC's main thread
                        if (forgeBootstrapAvailable && forgeBootstrapRender != null && mcAddScheduledTask != null) {
                            try {
                                Object mc = Class.forName("net.minecraft.client.Minecraft")
                                    .getMethod("getMinecraft").invoke(null);
                                if (mc != null) {
                                    final java.lang.reflect.Method renderMethod = forgeBootstrapRender;
                                    Runnable renderRunnable = new Runnable() {
                                        public void run() {
                                            try { renderMethod.invoke(null); } catch (Exception ignored) {}
                                        }
                                    };
                                    mcAddScheduledTask.invoke(mc, renderRunnable);
                                }
                            } catch (Exception ignored) {}
                        } else if (inlineRendererReady && !javassistHookReady) {
                            // Use inline renderer ONLY if Javassist hook failed (tick-phase, invisible)
                            scheduleInlineRender();
                        }

                        // Read HUD text for chat-based fallback + Javassist state update
                        String hudText = (String) getHudText.invoke(null);
                        boolean guiOpen = (Boolean) isGuiOpen.invoke(null);

                        // Push state to Javassist hook (renders in render phase — correct!)
                        updateJavassistHookState(guiOpen, hudText);

                        if (!guiOpen && hudText != null && !hudText.isEmpty() && !hudText.equals(lastHudText)) {
                            lastHudText = hudText;
                            sendActionBarMessage(hudText);
                        }

                        if (guiOpen && !lastHudText.contains("[GUI]")) {
                            lastHudText = "[GUI] " + hudText;
                            sendActionBarMessage("\u00a7a[SwitchLite GUI Open] \u00a77RShift=close");
                        }
                    } catch (Exception ignored) {}
                    Thread.sleep(50); // 20 Hz (was 2 Hz, upgraded for module processing)
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
                                    sendLocalMessage("Press Right Shift to toggle module status", GRAY);
                                    return; // done
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
    //  Right Shift Key Polling (LWJGL2 Keyboard)
    // ═══════════════════════════════════════════

    // Cached reflection for ForgeBootstrap.tick()/render()/onKey() calls
    private static java.lang.reflect.Method forgeBootstrapTick = null;
    private static java.lang.reflect.Method forgeBootstrapOnKey = null;
    private static java.lang.reflect.Method forgeBootstrapRender = null;
    private static java.lang.reflect.Method forgeBootstrapOnDisconnect = null;
    private static java.lang.reflect.Method mcAddScheduledTask = null;
    private static boolean forgeBootstrapAvailable = false;

    // ═══════════════════════════════════════════
    //  Inline HUD Renderer (pure reflection, no ForgeGradle needed)
    // ═══════════════════════════════════════════

    // Cached MC reflection handles for rendering
    private static java.lang.reflect.Method mcGetMinecraft = null;
    private static java.lang.reflect.Field mcFontRenderer = null;
    private static java.lang.reflect.Method fontDrawStringWithShadow = null;
    private static java.lang.reflect.Method glPushMatrix = null;
    private static java.lang.reflect.Method glPopMatrix = null;
    private static java.lang.reflect.Method glEnable = null;
    private static java.lang.reflect.Method glDisable = null;
    private static java.lang.reflect.Method glScalef = null;
    private static java.lang.reflect.Method glColor4f = null;
    private static java.lang.reflect.Field mcDisplayWidth = null;
    private static java.lang.reflect.Field mcDisplayHeight = null;
    private static java.lang.reflect.Field mcCurrentScreen = null;
    private static boolean inlineRendererReady = false;
    private static volatile boolean renderScheduled = false; // prevent queue flood
    private static boolean fontMethodUsesFloat = true; // true = draw(String,float,float,int), false = draw(String,int,int,int)
    private static int renderCallCount = 0; // diagnostic counter

    // ═══════════════════════════════════════════
    //  Javassist Render Hook — subclass GuiIngame in MC's ClassLoader
    // ═══════════════════════════════════════════

    private static Class<?> hookedGuiClass = null;      // the Javassist-generated class
    private static java.lang.reflect.Field hookedGuiVisibleField = null;  // static field on hooked class
    private static java.lang.reflect.Field hookedHudTextField = null;      // static field on hooked class
    private static volatile boolean javassistHookReady = false;

    private static void initInlineRenderer() {
        try {
            // Cache getMinecraft
            Class<?> mcClass = Class.forName("net.minecraft.client.Minecraft");
            for (String name : MC_GET_MC) {
                try {
                    java.lang.reflect.Method m = mcClass.getMethod(name);
                    Object mcInst = m.invoke(null);
                    if (mcInst != null) { mcGetMinecraft = m; break; }
                } catch (Exception ignored) {}
            }
            if (mcGetMinecraft == null) { log("[Render] getMinecraft not found"); return; }

            // FontRenderer: mc.fontRendererObj / field_71466_p
            for (String fn : new String[]{"fontRendererObj", "field_71466_p"}) {
                try {
                    mcFontRenderer = mcClass.getField(fn);
                    break;
                } catch (Exception ignored) {}
            }
            if (mcFontRenderer == null) { log("[Render] fontRenderer field not found"); return; }

            // FontRenderer.drawStringWithShadow(String, float, float, int)
            Class<?> fontClass = mcFontRenderer.getType();
            log("[Render] fontClass = " + fontClass.getName());
            for (String mn : new String[]{"drawStringWithShadow", "func_78266_a"}) {
                try {
                    fontDrawStringWithShadow = fontClass.getMethod(mn, String.class, float.class, float.class, int.class);
                    fontMethodUsesFloat = true; // this path explicitly uses float
                    log("[Render] Found " + mn + " via getMethod (float)");
                    break;
                } catch (Exception ignored) {}
            }
            // Fallback: scan all methods for any draw* with (String, float, float, int)
            if (fontDrawStringWithShadow == null) {
                for (java.lang.reflect.Method m : fontClass.getMethods()) {
                    Class<?>[] params = m.getParameterTypes();
                    if (params.length == 4
                        && params[0] == String.class
                        && (params[1] == float.class || params[1] == int.class)
                        && (params[2] == float.class || params[2] == int.class)
                        && params[3] == int.class
                        && m.getReturnType() == int.class) {
                        fontDrawStringWithShadow = m;
                        fontMethodUsesFloat = (params[1] == float.class);
                        log("[Render] Found draw method " + m.getName() + " via scan (x/y=" + (fontMethodUsesFloat ? "float" : "int") + ")");
                        break;
                    }
                }
            }
            if (fontDrawStringWithShadow == null) {
                // Last resort: try drawString (without shadow) as fallback
                for (String mn : new String[]{"drawString", "func_78266_b"}) {
                    try {
                        fontDrawStringWithShadow = fontClass.getMethod(mn, String.class, float.class, float.class, int.class);
                        log("[Render] Using drawString (no shadow) as fallback");
                        break;
                    } catch (Exception ignored) {}
                }
            }
            if (fontDrawStringWithShadow == null) {
                StringBuilder sb = new StringBuilder("[Render] No draw method found. Font methods with 'draw': ");
                for (java.lang.reflect.Method m : fontClass.getMethods()) {
                    if (m.getName().contains("draw")) {
                        sb.append(m.getName()).append("(");
                        for (Class<?> p : m.getParameterTypes()) sb.append(p.getSimpleName()).append(",");
                        sb.append(") ");
                    }
                }
                log(sb.toString());
                return;
            }

            // GL11 static methods
            Class<?> gl11Class = Class.forName("org.lwjgl.opengl.GL11");
            glPushMatrix = gl11Class.getMethod("glPushMatrix");
            glPopMatrix = gl11Class.getMethod("glPopMatrix");
            glScalef = gl11Class.getMethod("glScalef", float.class, float.class, float.class);
            glColor4f = gl11Class.getMethod("glColor4f", float.class, float.class, float.class, float.class);
            glEnable = gl11Class.getMethod("glEnable", int.class);
            glDisable = gl11Class.getMethod("glDisable", int.class);

            // MC display size: mc.displayWidth / field_71313_c, displayHeight / field_71314_d
            for (String fn : new String[]{"displayWidth", "field_71313_c"}) {
                try { mcDisplayWidth = mcClass.getField(fn); break; } catch (Exception ignored) {}
            }
            for (String fn : new String[]{"displayHeight", "field_71314_d"}) {
                try { mcDisplayHeight = mcClass.getField(fn); break; } catch (Exception ignored) {}
            }

            // mc.currentScreen / field_71462_r (null when in-game)
            for (String fn : new String[]{"currentScreen", "field_71462_r"}) {
                try { mcCurrentScreen = mcClass.getField(fn); break; } catch (Exception ignored) {}
            }

            // Cache mc.addScheduledTask
            for (String mn : new String[]{"addScheduledTask", "func_152344_a"}) {
                try {
                    mcAddScheduledTask = mcClass.getMethod(mn, Runnable.class);
                    break;
                } catch (Exception ignored) {}
            }

            inlineRendererReady = true;
            log("[Render] Inline HUD renderer initialized successfully");
        } catch (ClassNotFoundException e) {
            log("[Render] LWJGL/MC classes not found — renderer disabled");
        } catch (Exception e) {
            log("[Render] Init error: " + e.getMessage());
        }
    }

    /**
     * Renders HUD text directly using MC FontRenderer + GL11, scheduled on MC render thread.
     * This is the fallback when ForgeBootstrap is not available.
     */
    private static void scheduleInlineRender() {
        if (!inlineRendererReady || mcAddScheduledTask == null || renderScheduled) return;
        try {
            Object mc = mcGetMinecraft.invoke(null);
            if (mc == null) return;
            // Don't render when a screen (inventory, pause, etc.) is open
            if (mcCurrentScreen != null && mcCurrentScreen.get(mc) != null) return;

            final String hudText = lastHudText;
            final boolean guiOpen = guiVisible;
            renderScheduled = true;

            Runnable renderTask = new Runnable() {
                public void run() {
                    try {
                        Object mc = mcGetMinecraft.invoke(null);
                        if (mc == null) return;
                        Object fontRenderer = mcFontRenderer.get(mc);
                        if (fontRenderer == null) return;

                        int width = (mcDisplayWidth != null) ? mcDisplayWidth.getInt(mc) : 854;
                        int height = (mcDisplayHeight != null) ? mcDisplayHeight.getInt(mc) : 480;

                        float scale = 1.0f;
                        // On high-res screens, scale up so text is readable
                        if (width > 1200) scale = 1.5f;
                        else if (width > 900) scale = 1.2f;

                        // Push GL state
                        glPushMatrix.invoke(null);
                        glScalef.invoke(null, scale, scale, scale);

                        int y = 4; // start near top-left (in scaled coords)
                        int white = 0xFFFFFF;
                        int green = 0x55FF55;
                        int gray = 0xAAAAAA;
                        int gold = 0xFFFF55;

                        // Header line
                        String header = "\u00a7a[SwitchLite] \u00a7f" + (guiOpen ? "GUI: ON" : "v" + System.currentTimeMillis() % 1000);
                        // Use correct param types: float or int depending on what the scan found
                        if (fontMethodUsesFloat) {
                            fontDrawStringWithShadow.invoke(fontRenderer, header, 4.0f, (float)y, gold);
                        } else {
                            fontDrawStringWithShadow.invoke(fontRenderer, header, 4, y, gold);
                        }
                        y += 12;

                        if (guiOpen) {
                            if (fontMethodUsesFloat) {
                                fontDrawStringWithShadow.invoke(fontRenderer, "\u00a77Right Shift = toggle", 4.0f, (float)y, gray);
                            } else {
                                fontDrawStringWithShadow.invoke(fontRenderer, "\u00a77Right Shift = toggle", 4, y, gray);
                            }
                            y += 12;
                            if (fontMethodUsesFloat) {
                                fontDrawStringWithShadow.invoke(fontRenderer, "\u00a77Modules active", 4.0f, (float)y, green);
                            } else {
                                fontDrawStringWithShadow.invoke(fontRenderer, "\u00a77Modules active", 4, y, green);
                            }
                        }

                        // If HUD text has content, show it below
                        if (hudText != null && !hudText.isEmpty() && !"".equals(hudText)) {
                            y += 4;
                            if (fontMethodUsesFloat) {
                                fontDrawStringWithShadow.invoke(fontRenderer, hudText, 4.0f, (float)y, white);
                            } else {
                                fontDrawStringWithShadow.invoke(fontRenderer, hudText, 4, y, white);
                            }
                        }

                        // Restore GL state
                        glPopMatrix.invoke(null);

                        // Periodic diagnostic (every ~5s = 100 calls at 20Hz)
                        renderCallCount++;
                        if (renderCallCount % 100 == 1) {
                            log("[Render] Render OK, calls=" + renderCallCount + " res=" + width + "x" + height + " scale=" + scale);
                        }
                    } catch (Exception e) {
                        renderCallCount++;
                        if (renderCallCount <= 3) {
                            log("[Render] Render error: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                        }
                    }
                    finally {
                        renderScheduled = false;
                    }
                }
            };
            mcAddScheduledTask.invoke(mc, renderTask);
        } catch (Exception e) {
            if (renderCallCount <= 3) {
                log("[Render] scheduleInlineRender error: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
            renderScheduled = false;
        }
    }

    private static void cacheForgeBootstrapMethods() {
        try {
            Class<?> fbClass = Class.forName("io.switchlite.adapter.forge.v1_8_9.ForgeBootstrap");
            forgeBootstrapTick = fbClass.getMethod("tick");
            forgeBootstrapOnKey = fbClass.getMethod("onKey", int.class, boolean.class);
            forgeBootstrapRender = fbClass.getMethod("render");
            forgeBootstrapOnDisconnect = fbClass.getMethod("onDisconnect");
            forgeBootstrapAvailable = true;
            log("[Agent] ForgeBootstrap methods cached (pure reflection mode)");
        } catch (Exception e) {
            log("[Agent] ForgeBootstrap not available: " + e.getMessage());
        }
    }

    /**
     * Javassist Render Hook — creates a GuiIngame subclass in MC's ClassLoader
     * that overrides renderGameOverlay. This runs in the RENDER phase (not tick),
     * so GL text rendering actually appears on screen.
     *
     * No Instrumentation needed — uses Javassist CtClass.toClass(loader) to
     * define the subclass directly in MC's ClassLoader.
     *
     * State communication: Agent writes to static volatile fields on the
     * generated class via reflection (cross-ClassLoader compatible).
     */
    private static void hookRenderViaJavassist() {
        try {
            Class<?> mcClass = Class.forName("net.minecraft.client.Minecraft");
            Object mcInst = null;
            String getMcName = null;
            for (String name : MC_GET_MC) {
                try {
                    java.lang.reflect.Method m = mcClass.getMethod(name);
                    Object obj = m.invoke(null);
                    if (obj != null) { mcInst = obj; getMcName = name; break; }
                } catch (Exception ignored) {}
            }
            if (mcInst == null) { log("[Javassist] getMinecraft returned null"); return; }

            ClassLoader mcClassLoader = mcInst.getClass().getClassLoader();
            log("[Javassist] MC ClassLoader: " + mcClassLoader.getClass().getName());

            // ── Step 1: Detect actual runtime method/field names ──
            String fontRendererFieldName = null;
            for (String fn : new String[]{"fontRendererObj", "field_71466_p"}) {
                try { mcClass.getField(fn); fontRendererFieldName = fn; break; } catch (Exception ignored) {}
            }
            if (fontRendererFieldName == null) { log("[Javassist] fontRenderer field not found"); return; }

            String ingameGuiFieldName = null;
            for (String fn : new String[]{"ingameGUI", "field_71438_f"}) {
                try { mcClass.getField(fn); ingameGuiFieldName = fn; break; } catch (Exception ignored) {}
            }
            if (ingameGuiFieldName == null) { log("[Javassist] ingameGUI field not found"); return; }

            // Detect renderGameOverlay method name on GuiIngame
            String renderOverlayMethodName = null;
            Class<?> guiIngameClass = Class.forName("net.minecraft.client.gui.GuiIngame");
            for (java.lang.reflect.Method m : guiIngameClass.getMethods()) {
                if (m.getReturnType() == void.class
                    && m.getParameterTypes().length == 1
                    && m.getParameterTypes()[0] == float.class) {
                    // Accept known names — MCP, SRG, or any void(float) method on GuiIngame
                    String n = m.getName();
                    if (n.equals("renderGameOverlay") || n.equals("func_175180_a")
                        || (n.startsWith("func_") && !n.contains("chat"))) {
                        renderOverlayMethodName = n;
                        log("[Javassist] renderGameOverlay candidate: " + n);
                        break;
                    }
                }
            }
            if (renderOverlayMethodName == null) { log("[Javassist] renderGameOverlay method not found"); return; }
            log("[Javassist] renderGameOverlay = " + renderOverlayMethodName);

            // Detect FontRenderer draw method (with shadow preferred)
            Class<?> fontClass = Class.forName("net.minecraft.client.gui.FontRenderer");
            String drawMethodName = null;
            boolean drawUsesFloat = true;
            // Try drawStringWithShadow first
            for (String mn : new String[]{"drawStringWithShadow", "func_78266_a"}) {
                try {
                    fontClass.getMethod(mn, String.class, float.class, float.class, int.class);
                    drawMethodName = mn; drawUsesFloat = true; break;
                } catch (Exception ignored) {}
            }
            // Fallback: scan for any (String, num, num, int) -> int
            if (drawMethodName == null) {
                for (java.lang.reflect.Method m : fontClass.getMethods()) {
                    Class<?>[] p = m.getParameterTypes();
                    if (p.length == 4 && p[0] == String.class
                        && (p[1] == float.class || p[1] == int.class)
                        && (p[2] == float.class || p[2] == int.class)
                        && p[3] == int.class && m.getReturnType() == int.class) {
                        drawMethodName = m.getName();
                        drawUsesFloat = (p[1] == float.class);
                        break;
                    }
                }
            }
            if (drawMethodName == null) { log("[Javassist] FontRenderer draw method not found"); return; }
            log("[Javassist] draw method = " + drawMethodName + " (float=" + drawUsesFloat + ")");

            // ── Step 2: Create GuiIngame subclass via Javassist ──
            javassist.ClassPool pool = javassist.ClassPool.getDefault();
            pool.appendClassPath(new javassist.LoaderClassPath(mcClassLoader));

            javassist.CtClass ctGuiIngame = pool.get("net.minecraft.client.gui.GuiIngame");
            javassist.CtClass ctMinecraft = pool.get("net.minecraft.client.Minecraft");
            javassist.CtClass ctFontRenderer = pool.get("net.minecraft.client.gui.FontRenderer");
            javassist.CtClass ctFloat = pool.get("float");

            javassist.CtClass ctHook = pool.makeClass("sl_switchlite_hud");
            ctHook.setSuperclass(ctGuiIngame);

            // Static volatile fields for cross-ClassLoader state communication
            javassist.CtField guiVisField = new javassist.CtField(
                javassist.CtClass.booleanType, "slGuiVisible", ctHook);
            guiVisField.setModifiers(java.lang.reflect.Modifier.PUBLIC
                | java.lang.reflect.Modifier.STATIC | java.lang.reflect.Modifier.VOLATILE);
            ctHook.addField(guiVisField, javassist.CtField.Initializer.constant(false));

            javassist.CtField hudTxtField = new javassist.CtField(
                pool.get("java.lang.String"), "slHudText", ctHook);
            hudTxtField.setModifiers(java.lang.reflect.Modifier.PUBLIC
                | java.lang.reflect.Modifier.STATIC | java.lang.reflect.Modifier.VOLATILE);
            ctHook.addField(hudTxtField, javassist.CtField.Initializer.constant(""));

            // Constructor: forward to GuiIngame(Minecraft)
            javassist.CtConstructor ctor = new javassist.CtConstructor(
                new javassist.CtClass[]{ctMinecraft}, ctHook);
            ctor.setBody("{ super($1); }");
            ctHook.addConstructor(ctor);

            // Override renderGameOverlay(float)
            String xType = drawUsesFloat ? "float" : "int";
            String renderBody =
                "{ super." + renderOverlayMethodName + "($1);" +
                "  try {" +
                "    net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft." + getMcName + "();" +
                "    net.minecraft.client.gui.FontRenderer fr = mc." + fontRendererFieldName + ";" +
                "    if (fr == null) return;" +
                "    " + xType + " y = 4;" +
                "    String hdr = slGuiVisible ? "\u00a7a[SwitchLite] \u00a7fGUI: ON" : "\u00a7a[SwitchLite] \u00a7fv0.1-alpha";" +
                "    fr." + drawMethodName + "(hdr, (" + xType + ")4, y, 0xFFFF55);" +
                "    y += (" + xType + ")12;" +
                "    if (slGuiVisible) {" +
                "      fr." + drawMethodName + "("\u00a77Right Shift = toggle", (" + xType + ")4, y, 0xAAAAAA);" +
                "      y += (" + xType + ")12;" +
                "    }" +
                "    if (slHudText != null && slHudText.length() > 0) {" +
                "      y += (" + xType + ")4;" +
                "      fr." + drawMethodName + "(slHudText, (" + xType + ")4, y, 0xFFFFFF);" +
                "    }" +
                "  } catch (Exception e) {}" +
                "}";

            javassist.CtMethod renderMethod = new javassist.CtMethod(
                javassist.CtClass.voidType, renderOverlayMethodName,
                new javassist.CtClass[]{ctFloat}, ctHook);
            renderMethod.setModifiers(java.lang.reflect.Modifier.PUBLIC);
            renderMethod.setBody(renderBody);
            ctHook.addMethod(renderMethod);

            // ── Step 3: Load class into MC's ClassLoader & create instance ──
            Class<?> hookClass = ctHook.toClass(mcClassLoader);
            ctHook.detach(); // release CtClass memory

            java.lang.reflect.Constructor<?> hookCtor = hookClass.getConstructor(
                Class.forName("net.minecraft.client.Minecraft", false, mcClassLoader));
            Object hookInstance = hookCtor.newInstance(mcInst);

            // ── Step 4: Replace mc.ingameGUI with our hooked instance ──
            java.lang.reflect.Field ingameField = mcClass.getField(ingameGuiFieldName);
            Object oldGui = ingameField.get(mcInst);
            ingameField.set(mcInst, hookInstance);

            // ── Step 5: Cache references for state updates ──
            hookedGuiClass = hookClass;
            hookedGuiVisibleField = hookClass.getField("slGuiVisible");
            hookedHudTextField = hookClass.getField("slHudText");
            javassistHookReady = true;

            log("[Javassist] HUD hook installed! class=" + hookClass.getName()
                + " render=" + renderOverlayMethodName + " draw=" + drawMethodName
                + " replaced oldGui=" + (oldGui != null ? oldGui.getClass().getSimpleName() : "null"));

        } catch (ClassNotFoundException e) {
            log("[Javassist] MC classes not found — " + e.getMessage());
        } catch (javassist.CannotCompileException e) {
            log("[Javassist] Compile error: " + e.getMessage());
        } catch (Exception e) {
            log("[Javassist] Hook failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /**
     * Update the Javassist-hooked GuiIngame's state fields.
     * Called from HudTick to push guiVisible + hudText into MC's ClassLoader.
     */
    private static void updateJavassistHookState(boolean guiOpen, String hudText) {
        if (!javassistHookReady) return;
        try {
            hookedGuiVisibleField.set(null, guiOpen);
            hookedHudTextField.set(null, hudText != null ? hudText : "");
        } catch (Exception ignored) {}
    }

    private static void startKeyPollThread() {
        keyPollThread = new Thread(() -> {
            log("[KeyPoll] Thread started, polling LWJGL2 Keyboard");
            try {
                Class<?> keyboardClass = Class.forName("org.lwjgl.input.Keyboard");
                java.lang.reflect.Method isKeyDown = keyboardClass.getMethod("isKeyDown", int.class);
                java.lang.reflect.Method getEventKey = keyboardClass.getMethod("getEventKey");
                java.lang.reflect.Method getEventKeyState = keyboardClass.getMethod("getEventKeyState");
                java.lang.reflect.Method isNext = keyboardClass.getMethod("next");

                boolean keyWasDown = false;
                while (running) {
                    try {
                        // Poll all LWJGL2 key events and dispatch to ForgeBootstrap
                        boolean nextResult = (Boolean) isNext.invoke(null);
                        if (nextResult) {
                            int lwjglCode = (Integer) getEventKey.invoke(null);
                            boolean pressed = (Boolean) getEventKeyState.invoke(null);
                            if (lwjglCode != 0 && forgeBootstrapAvailable && forgeBootstrapOnKey != null) {
                                forgeBootstrapOnKey.invoke(null, lwjglCode, pressed);
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

    private static void onToggleKeyPressed() {
        guiVisible = !guiVisible;
        String status = guiVisible ? "ON" : "OFF";
        log("[KeyPoll] Right Shift pressed — GUI toggled: " + status);

        // Immediately update Javassist hook state (no 50ms delay)
        updateJavassistHookState(guiVisible, lastHudText);

        // Dispatch to EventBridge so module-layer keyListeners (ClickGUI) receive it.
        // GLFW RIGHT_SHIFT = 344, pressed = true (this is a key-down edge).
        try {
            Class<?> ebClass = Class.forName("io.switchlite.adapter.common.api.EventBridge");
            java.lang.reflect.Method onKeyMethod = ebClass.getMethod("onKey", int.class, boolean.class);
            onKeyMethod.invoke(null, 344, true); // GLFW RIGHT_SHIFT, pressed
        } catch (ClassNotFoundException e) {
            // EventBridge not available — adapter:common not loaded
        } catch (Exception ignored) {}

        // Local-only message — no server interaction, no kick risk
        if (guiVisible) {
            sendLocalMessage("Modules: ACTIVE (alive at " + System.currentTimeMillis() + ")", GREEN);
        } else {
            sendLocalMessage("Modules: DISABLED", RED);
        }
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

            log("[Chat] Failed to send local message — no method found");
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

    /**
     * Send a message to the action bar (above hotbar).
     * Uses GuiIngame.displayActionBar(IChatComponent) / func_146237_a.
     * This is the ideal HUD location — doesn't clutter chat.
     */
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

            // GuiIngame.displayActionBar — "ingameGUI"/"field_71438_f"
            String[] ingameGuiFields = {"ingameGUI", "field_71438_f"};
            for (String fn : ingameGuiFields) {
                try {
                    java.lang.reflect.Field f = mcClass.getField(fn);
                    Object ingameGUI = f.get(mc);
                    if (ingameGUI == null) continue;
                    // func_146237_a = displayActionBar(IChatComponent)
                    String[] displayNames = {"displayActionBar", "func_146237_a",
                                              "setOverlayMessage", "func_110326_a"};
                    for (String mn : displayNames) {
                        try {
                            java.lang.reflect.Method m = ingameGUI.getClass().getMethod(mn, ichatCompClass);
                            m.invoke(ingameGUI, chatComp);
                            return; // success
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
