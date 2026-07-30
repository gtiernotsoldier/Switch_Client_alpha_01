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
 *   - Chat messages → ForgeBootstrap / modules
 *   - GUI state → EventBridge.isGuiOpen / ClickGUI
 */
public class Agent {

    private static Instrumentation instrumentation;
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

        coreInit(inst, detectConfigPath());
    }

    public static void premain(String agentArgs, Instrumentation inst) {
        initLogFile();
        log("[SwitchLite Agent] Loaded at JVM startup");
        coreInit(inst, detectConfigPath());
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

        String configPath = configDir + File.separator + "switchlite-config.properties";
        coreInit(null, configPath);
    }

    // ═══════════════════════════════════════════
    //  Core initialization (shared by all entry points)
    // ═══════════════════════════════════════════

    private static void coreInit(Instrumentation inst, String configPath) {
        // 1. Load config
        String platform = "Unknown";
        String version = "Unknown";

        if (configPath != null) {
            File configFile = new File(configPath);
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
            } else {
                log("[Agent] Config file not found, using defaults");
            }
        } else {
            log("[Agent] No config path provided, using defaults");
        }

        log("[Agent] Platform: " + platform + " | Version: " + version);

        // 2. Load mappings
        String mappingsDir = detectMappingsDir();
        try {
            MappingLoader.loadMappings(platform, version, mappingsDir);
            log("[Agent] Mappings loaded OK");
        } catch (Exception e) {
            log("[Agent] Mapping load FAILED (non-fatal for verification): " + e.getMessage());
        }

        MappingContext.initialize();
        log("[Agent] MappingContext initialized");

        // 3. Initialize adapter layer (AgentBridge — registers modules)
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

        // 4. Initialize Forge adapter (pure reflection — no ForgeGradle needed)
        if ("Forge".equals(platform)) {
            try {
                Class<?> bootstrapClass = Class.forName("io.switchlite.adapter.forge.v1_8_9.ForgeBootstrap");
                Object fbInstance = bootstrapClass.getField("INSTANCE").get(null);
                Method initMethod = bootstrapClass.getMethod("init");
                initMethod.invoke(fbInstance);
                log("[Agent] ForgeBootstrap.init() complete (pure reflection mode)");

                // Cache method refs for tick/render/onKey dispatch
                cacheForgeBootstrapMethods(bootstrapClass, fbInstance);
            } catch (ClassNotFoundException e) {
                log("[Agent] ForgeBootstrap not in classpath — HUD via Display.update() hook");
            } catch (Exception e) {
                log("[Agent] ForgeBootstrap init failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }

        // 5. Install Transformer hook (Javassist's job, not Agent's)
        boolean hookInstalled = Transformer.install(inst);
        if (hookInstalled) {
            log("[Agent] Render path: Transformer + RenderHook → ForgeBootstrap.render() (every frame, stealthy)");
        } else {
            log("[Agent] Render path: addScheduledTask → ForgeBootstrap.render() (20Hz fallback, less stealthy)");
        }

        // 6. Start threads
        startKeyPollThread();
        startHudTickThread();

        log("[SwitchLite Agent] Ready — key listener active");
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

    // Forge 1.8.9 SRG names
    private static final String[] MC_GET_MC = {"getMinecraft", "func_71410_x"};

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

                        // Chat-based HUD fallback (action bar) — safety net when GL overlay is not visible
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
                Method getEventKey = keyboardClass.getMethod("getEventKey");
                Method getEventKeyState = keyboardClass.getMethod("getEventKeyState");
                Method isNext = keyboardClass.getMethod("next");

                while (running) {
                    try {
                        // Poll all LWJGL2 key events and dispatch to ForgeBootstrap
                        // All keys (including Right Shift) go through the proper pipeline:
                        // KeyPollThread → ForgeBootstrap.onKey() → EventBridge.onKey() → ClickGUI
                        boolean nextResult = (Boolean) isNext.invoke(null);
                        if (nextResult) {
                            int lwjglCode = (Integer) getEventKey.invoke(null);
                            boolean pressed = (Boolean) getEventKeyState.invoke(null);
                            if (lwjglCode != 0 && forgeBootstrapAvailable && forgeBootstrapOnKey != null && forgeBootstrapInstance != null) {
                                forgeBootstrapOnKey.invoke(forgeBootstrapInstance, lwjglCode, pressed);
                            }
                        }
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
                while (running) {
                    try {
                        Thread.sleep(2000);
                        Class.forName("org.lwjgl.input.Keyboard");
                        log("[KeyPoll] LWJGL Keyboard found! Restarting poll...");
                        startKeyPollThread();
                        return;
                    } catch (ClassNotFoundException e) {
                        // Keep retrying
                    }
                }
            } catch (InterruptedException ignored) {}
        }, "SwitchLite-KeyPollRetry");
        retryThread.setDaemon(true);
        retryThread.start();
    }

    // ═══════════════════════════════════════════
    //  Action bar message — fallback HUD display
    // ═══════════════════════════════════════════

    private static String mcGetInstance;

    /**
     * Send a message to the action bar (client-side only).
     * Used as a HUD text fallback when GL overlay is not available.
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
