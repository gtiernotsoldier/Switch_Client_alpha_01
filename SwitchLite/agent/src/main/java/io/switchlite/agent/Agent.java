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
        log("[SwitchLite Agent] Attached to running JVM (args=" + agentArgs + ")");
        instrumentation = inst;

        // If called via attach API for retransform
        if ("retransform-display".equals(agentArgs)) {
            try {
                // Critical: add agent.jar to bootstrap classloader search path.
                // Display is loaded by bootstrap CL. After retransform, the new
                // bytecode references RenderHook — bootstrap CL must be able to find it.
                String agentJar = System.getProperty("java.io.tmpdir") + "/switchlite-agent.jar";
                java.io.File jarFile = new java.io.File(agentJar);
                if (!jarFile.exists()) {
                    jarFile = new java.io.File(agentJar.replace("/", "\\"));
                }
                if (jarFile.exists()) {
                    inst.appendToBootstrapClassLoaderSearch(new java.util.jar.JarFile(jarFile));
                    log("[Agent] Agent jar appended to bootstrap classloader: " + jarFile.getAbsolutePath());
                } else {
                    log("[Agent] WARNING: agent.jar not found for bootstrap CL append");
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
            while (cause != null) {
                log("[Agent]   Caused by: " + cause.getClass().getSimpleName() + ": " + cause.getMessage());
                for (StackTraceElement ste : cause.getStackTrace()) {
                    if (ste.getClassName().startsWith("io.switchlite")) {
                        log("[Agent]     at " + ste.toString());
                    }
                }
                cause = cause.getCause();
            }
            // Don't abort — continue boot
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

                // Disable old EventBridge.onTick(null,null) dispatch — ForgeBootstrap.tick() handles it now
                // The HUD thread still reads hudText for action bar fallback
            } catch (ClassNotFoundException e) {
                log("[Agent] ForgeBootstrap not in classpath — HUD via Display.update() hook");
            } catch (Exception e) {
                log("[Agent] ForgeBootstrap init failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }

        // ========== Hook Display.update() for HUD rendering ==========
        // bootstrap() has no Instrumentation, so we use Javassist directly
        // to redefine the already-loaded Display class.
        hookDisplayUpdate();

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

    /**
     * Direct Javassist redefine of Display.update() — no Instrumentation needed.
     *
     * Since bootstrap() is called via JNI (not premain/agentmain), we don't have
     * an Instrumentation instance. Display is already loaded, so the normal
     * ClassFileTransformer won't fire. Strategy:
     *   1. Instrumentation.retransformClasses (if inst available)
     *   2. com.sun.tools.attach to self-attach, then loadAgent to get Instrumentation
     */
    private static void hookDisplayUpdate() {
        try {
            Class<?> displayClass = Class.forName("org.lwjgl.opengl.Display");
            log("[Hook] Display class found");

            // Strategy 1: If we somehow have Instrumentation, use retransform
            if (instrumentation != null && instrumentation.isModifiableClass(displayClass)) {
                try {
                    instrumentation.addTransformer(new Transformer(), true);
                    instrumentation.retransformClasses(displayClass);
                    log("[Hook] Display.update() hooked via Instrumentation.retransformClasses");
                    return;
                } catch (Exception e) {
                    log("[Hook] Retransform failed: " + e.getMessage() + " — trying direct redefine");
                }
            }

            // Strategy 2: Use com.sun.tools.attach to get Instrumentation for this JVM
            try {
                // On JDK 8, tools.jar (containing attach API) is not on classpath by default.
                // Add it dynamically.
                ensureToolsJar();

                String pid = getProcessPid();
                if (pid != null) {
                    log("[Hook] Trying attach API for PID " + pid + "...");
                    Class<?> vmClass = Class.forName("com.sun.tools.attach.VirtualMachine");
                    java.lang.reflect.Method attachMethod = vmClass.getMethod("attach", String.class);
                    Object vm = attachMethod.invoke(null, pid);

                    String agentJarPath = System.getProperty("java.io.tmpdir") + "\\switchlite-agent.jar";
                    java.io.File testJar = new java.io.File(agentJarPath);
                    if (!testJar.exists()) {
                        agentJarPath = System.getProperty("java.io.tmpdir") + "/switchlite-agent.jar";
                        testJar = new java.io.File(agentJarPath);
                    }
                    if (testJar.exists()) {
                        java.lang.reflect.Method loadAgentMethod = vmClass.getMethod("loadAgent", String.class, String.class);
                        loadAgentMethod.invoke(vm, agentJarPath, "retransform-display");
                        log("[Hook] Agent re-attached via attach API — agentmain will handle hook");
                        java.lang.reflect.Method detachMethod = vmClass.getMethod("detach");
                        detachMethod.invoke(vm);
                        return;
                    } else {
                        log("[Hook] agent.jar not found at " + agentJarPath);
                    }
                }
            } catch (Exception e) {
                log("[Hook] Attach API failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            }

        } catch (ClassNotFoundException e) {
            log("[Hook] Display class not found — LWJGL not loaded yet?");
        } catch (Throwable t) {
            log("[Hook] Failed: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private static String getProcessPid() {
        try {
            Class<?> rtMxBeanClass = Class.forName("java.lang.management.RuntimeMXBean");
            java.lang.reflect.Method getNameMethod = rtMxBeanClass.getMethod("getName");
            Object rtMxBean = java.lang.management.ManagementFactory.getRuntimeMXBean();
            String name = (String) getNameMethod.invoke(rtMxBean);
            int at = name.indexOf('@');
            return at > 0 ? name.substring(0, at) : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * On JDK 8, com.sun.tools.attach lives in tools.jar which is not on
     * the default classpath. This method adds it dynamically so we can
     * use VirtualMachine.attach() to obtain an Instrumentation instance.
     */
    private static volatile boolean toolsJarLoaded = false;

    private static void ensureToolsJar() {
        if (toolsJarLoaded) return;
        // Check if already available
        try {
            Class.forName("com.sun.tools.attach.VirtualMachine");
            toolsJarLoaded = true;
            return;
        } catch (ClassNotFoundException ignored) {}

        String javaHome = System.getProperty("java.home");
        // java.home points to JRE dir; tools.jar is in the parent (JDK dir)
        java.io.File toolsJar = new java.io.File(javaHome, "../lib/tools.jar");
        if (!toolsJar.exists()) {
            // Some installs have it directly in java.home/lib
            toolsJar = new java.io.File(javaHome, "lib/tools.jar");
        }
        if (toolsJar.exists()) {
            try {
                java.net.URL jarUrl = toolsJar.toURI().toURL();
                java.lang.reflect.Method addUrl = java.net.URLClassLoader.class
                    .getDeclaredMethod("addURL", java.net.URL.class);
                addUrl.setAccessible(true);
                // Use the system classloader (or ext classloader) to load it
                ClassLoader sysCl = ClassLoader.getSystemClassLoader();
                addUrl.invoke(sysCl, jarUrl);
                toolsJarLoaded = true;
                log("[Hook] tools.jar added to classpath: " + toolsJar.getAbsolutePath());
            } catch (Exception e) {
                log("[Hook] Failed to add tools.jar: " + e.getMessage());
            }
        } else {
            log("[Hook] tools.jar not found (searched " + javaHome + ")");
        }
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

                        // Read HUD text for chat-based fallback + push state to RenderHook
                        String hudText = (String) getHudText.invoke(null);
                        boolean guiOpen = (Boolean) isGuiOpen.invoke(null);

                        // Push state via System.getProperties (cross-classloader safe)
                        System.setProperty("switchlite.guiOpen", String.valueOf(guiOpen));
                        System.setProperty("switchlite.hudText", hudText != null ? hudText : "");

                        // Queue a render task on the MC main thread (the only thread with GL context)
                        dispatchRender();

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

    /**
     * Queue a render task on the MC main thread via addScheduledTask.
     * This is the ONLY thread that holds the OpenGL context.
     * Delegates to ForgeBootstrap.render() for proper GL state management.
     */
    private static void dispatchRender() {
        try {
            // If ForgeBootstrap is available, delegate rendering to it
            if (forgeBootstrapAvailable && forgeBootstrapRender != null) {
                forgeBootstrapRender.invoke(null);
                return;
            }

            // Fallback: use addScheduledTask + drawOverlay (limited, no GL state management)
            Class<?> mcClass = Class.forName("net.minecraft.client.Minecraft");
            if (mcFactory == null) {
                for (String name : MC_GET_MC) {
                    try { mcFactory = mcClass.getMethod(name); break; }
                    catch (NoSuchMethodException ignored) {}
                }
                if (mcFactory == null) { secondLog("[Render] No Minecraft factory method"); return; }
            }
            Object mc = mcFactory.invoke(null);
            if (mc == null) { secondLog("[Render] MC instance null — not loaded"); return; }

            java.lang.reflect.Method addTask = null;
            for (String name : new String[]{"addScheduledTask", "func_152343_a"}) {
                try { addTask = mcClass.getMethod(name, Runnable.class); break; }
                catch (NoSuchMethodException ignored) {}
            }
            if (addTask == null) { secondLog("[Render] addScheduledTask not found"); return; }

            addTask.invoke(mc, (Runnable) () -> drawOverlay());
        } catch (Exception e) {
            secondLog("[Render] dispatch error: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private static void secondLog(String msg) {
        try { System.out.println(msg); } catch (Exception ignored) {}
        try {
            if (logStream != null) { logStream.println(msg); logStream.flush(); }
        } catch (Exception ignored) {}
    }

    /**
     * Draw the 2D overlay on the MC render thread.
     * FontRenderer is verified working via reflection (func_78276_b / func_175063_a).
     */
    private static void drawOverlay() {
        try {
            String hudText = System.getProperty("switchlite.hudText", "");
            boolean guiOpen = "true".equals(System.getProperty("switchlite.guiOpen", ""));

            if (hudText.isEmpty() && !guiOpen) return;

            Object fr = getFontRenderer();
            if (fr == null) return;

            // Scan for draw method — signatures vary (float vs int coords)
            java.lang.reflect.Method drawMethod = null;
            for (java.lang.reflect.Method m : fr.getClass().getMethods()) {
                if (m.getName().startsWith("draw") && m.getParameterCount() == 4) {
                    Class<?>[] p = m.getParameterTypes();
                    if (p[0] == String.class && (p[3] == int.class || p[3] == Integer.TYPE)) {
                        drawMethod = m;
                        break;
                    }
                }
            }
            if (drawMethod == null) return;

            Object[] args4 = new Object[4];
            args4[0] = (hudText.isEmpty() ? "SwitchLite" : hudText);
            args4[1] = 4; args4[2] = 4; args4[3] = 0xFFFFFF;
            drawMethod.invoke(fr, args4);

            if (guiOpen) {
                Object[] argsG = new Object[4];
                argsG[0] = "\u00a7a[GUI OPEN]";
                argsG[1] = 4; argsG[2] = 16; argsG[3] = 0x55FF55;
                drawMethod.invoke(fr, argsG);
            }
        } catch (Exception e) {
            secondLog("[Render] drawOverlay error: " + e.getClass().getSimpleName());
        }
    }

    private static Object getFontRenderer() {
        try {
            Class<?> mcClass = Class.forName("net.minecraft.client.Minecraft");
            if (mcFactory == null) return null;
            Object mc = mcFactory.invoke(null);
            if (mc == null) return null;

            for (java.lang.reflect.Field f : mcClass.getDeclaredFields()) {
                if (f.getType().getName().contains("FontRenderer")) {
                    return f.get(mc);
                }
            }
            for (java.lang.reflect.Field f : mcClass.getFields()) {
                if (f.getType().getName().contains("FontRenderer")) {
                    return f.get(mc);
                }
            }
        } catch (Exception ignored) {}
        return null;
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

    // Cached reflection for ForgeBootstrap.tick()/onKey()/render() calls
    private static java.lang.reflect.Method forgeBootstrapTick = null;
    private static java.lang.reflect.Method forgeBootstrapOnKey = null;
    private static java.lang.reflect.Method forgeBootstrapRender = null;
    private static java.lang.reflect.Method forgeBootstrapOnDisconnect = null;
    private static boolean forgeBootstrapAvailable = false;
    private static java.lang.reflect.Method mcFactory = null;

    // ═══════════════════════════════════════════
    //  ForgeBootstrap method cache
    // ═══════════════════════════════════════════

    private static void cacheForgeBootstrapMethods() {
        try {
            Class<?> fbClass = Class.forName("io.switchlite.adapter.forge.v1_8_9.ForgeBootstrap");
            forgeBootstrapTick = fbClass.getMethod("tick");
            forgeBootstrapOnKey = fbClass.getMethod("onKey", int.class, boolean.class);
            forgeBootstrapRender = fbClass.getMethod("render");
            forgeBootstrapOnDisconnect = fbClass.getMethod("onDisconnect");
            forgeBootstrapAvailable = true;
            log("[Agent] ForgeBootstrap methods cached including render() (pure reflection mode)");
        } catch (Exception e) {
            log("[Agent] ForgeBootstrap not available: " + e.getMessage());
        }
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

        // Immediately update HUD state via System.getProperties (cross-classloader safe)
        System.setProperty("switchlite.guiOpen", String.valueOf(guiVisible));
        System.setProperty("switchlite.hudText", lastHudText != null ? lastHudText : "");

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
