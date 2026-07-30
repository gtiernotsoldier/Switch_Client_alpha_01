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
 *
 * What Agent DOES NOT do (not its job):
 *   - GL rendering → ForgeBootstrap.render() via RenderBridge
 *   - Bytecode injection → Transformer + RenderBridge
 *   - Getting Instrumentation → Transformer.install()
 *   - Self-attach → Transformer.install()
 *   - Drawing overlays → ForgeBootstrap.render() via OverlayRenderer
 *   - Chat messages → ForgeBootstrap / modules
 *   - GUI state → EventBridge.isGuiOpen / ClickGUI
 *   - Transformer.install() failure → Agent reports error and exits, no fallback
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

                // Cache method refs for tick/onKey dispatch
                cacheForgeBootstrapMethods(bootstrapClass, fbInstance);
            } catch (ClassNotFoundException e) {
                log("[Agent] ForgeBootstrap not in classpath — HUD via Display.update() hook");
            } catch (Exception e) {
                log("[Agent] ForgeBootstrap init failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }

        // 5. Install Transformer hook (Javassist's job, not Agent's)
        boolean hookInstalled = Transformer.install(inst);
        if (!hookInstalled) {
            log("[Agent] FATAL: Transformer.install() failed — rendering pipeline is broken. Agent is pure dispatch, no fallback.");
            log("[Agent] Cannot operate without rendering. Shutting down.");
            running = false;
            return;
        }
        log("[Agent] Render path: Transformer + RenderBridge → ForgeBootstrap.render() (every frame, stealthy)");

        // 6. Start threads (dispatch only — no rendering)
        startKeyPollThread();
        startTickThread();

        log("[SwitchLite Agent] Ready — tick + key listener active");
    }

    // ═══════════════════════════════════════════
    //  ForgeBootstrap method cache (dispatch only)
    // ═══════════════════════════════════════════

    private static Object forgeBootstrapInstance = null;
    private static Method forgeBootstrapTick = null;
    private static Method forgeBootstrapOnKey = null;
    private static Method forgeBootstrapOnDisconnect = null;
    private static boolean forgeBootstrapAvailable = false;

    private static void cacheForgeBootstrapMethods(Class<?> fbClass, Object fbInstance) {
        try {
            forgeBootstrapInstance = fbInstance;
            forgeBootstrapTick = fbClass.getMethod("tick");
            forgeBootstrapOnKey = fbClass.getMethod("onKey", int.class, boolean.class);
            forgeBootstrapOnDisconnect = fbClass.getMethod("onDisconnect");
            forgeBootstrapAvailable = true;
            log("[Agent] ForgeBootstrap methods cached (tick, onKey, onDisconnect)");
        } catch (Exception e) {
            log("[Agent] ForgeBootstrap method cache failed: " + e.getMessage());
        }
    }

    // ═══════════════════════════════════════════
    //  Tick Thread — dispatch only, no rendering
    // ═══════════════════════════════════════════

    private static Thread tickThread = null;

    /**
     * Runs at 20Hz. Dispatches tick events to ForgeBootstrap.tick().
     *
     * This thread is PURE DISPATCH — it does NOT:
     * - Read HUD text or GUI state
     * - Write System.setProperty for rendering
     * - Call render() or dispatchRenderFallback()
     * - Send action bar messages
     *
     * All rendering is handled by the Javassist-injected RenderBridge → ForgeBootstrap.render() pipeline.
     */
    private static void startTickThread() {
        tickThread = new Thread(() -> {
            log("[TickThread] Started (20Hz tick dispatch)");
            while (running) {
                try {
                    if (forgeBootstrapAvailable && forgeBootstrapTick != null && forgeBootstrapInstance != null) {
                        forgeBootstrapTick.invoke(forgeBootstrapInstance);
                    }
                } catch (Exception ignored) {}
                try {
                    Thread.sleep(50); // 20 Hz
                } catch (InterruptedException ignored) {}
            }
        }, "SwitchLite-Tick");
        tickThread.setDaemon(true);
        tickThread.start();
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
