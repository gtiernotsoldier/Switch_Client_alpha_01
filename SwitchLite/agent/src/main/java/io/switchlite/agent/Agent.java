package io.switchlite.agent;

import java.io.*;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Properties;

// Direct imports — cut reflection bridges (#4, #5)
// agent.jar already bundles adapter:forge:v1_8_9 and adapter:common
import io.switchlite.adapter.forge.v1_8_9.ForgeBootstrap;
import io.switchlite.adapter.common.AgentBridge;

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
        log("[Agent] agentmain ClassLoader: " + Agent.class.getClassLoader().getClass().getName());
        log("[Agent] agentmain Thread: " + Thread.currentThread().getName());
        log("[Agent] agentmain Context CL: " + Thread.currentThread().getContextClassLoader().getClass().getName());
        instrumentation = inst;

        // Self-attach callback: Transformer asked us to retransform Display
        if ("retransform-display".equals(agentArgs)) {
            Transformer.handleRetransform(inst);
            return;
        }

        // JNI attach callback: payload.dll triggered agentmain via Windows Attach pipe
        // This means Agent.bootstrap() already ran (inst=null), but Transformer failed.
        // Now we have a real Instrumentation — install the hook.
        //
        // IMPORTANT: When agentmain() is called by the JPLIS agent, it loads this
        // Agent class using the system CL (via appendToSystemClassLoaderSearch).
        // The system CL cannot see LWJGL classes. Transformer.install() now uses
        // findDisplayClass() which tries multiple ClassLoaders to find Display.
        if ("jni-attach".equals(agentArgs)) {
            log("[Agent] JNI attach callback — installing Transformer hook with Instrumentation");
            if (Transformer.isInstalled()) {
                log("[Agent] Transformer already installed (duplicate attach), skipping");
                return;
            }
            boolean hookInstalled = Transformer.install(inst);
            if (!hookInstalled) {
                log("[Agent] FATAL: Transformer.install() failed even with Instrumentation from jni-attach");
                log("[Agent] The rendering pipeline is broken — HUD overlay will not appear");
            } else {
                log("[Agent] Transformer hook installed via jni-attach — rendering pipeline active");
            }
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
     *
     * In this mode, coreInit() receives inst=null. Transformer.install(null)
     * will fail (no Instrumentation available). The payload.dll then uses the
     * Windows Attach pipe protocol to trigger agentmain() with a proper
     * Instrumentation, which installs the Transformer hook.
     *
     * This two-phase approach is necessary because:
     *   1. JNI bootstrap has no Instrumentation
     *   2. Self-attach via VirtualMachine needs tools.jar (JDK only, MC uses JRE)
     *   3. The Windows Attach pipe protocol is implemented in C++ (no tools.jar)
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
                    // Fix: payload.dll writes version as "=1.8.9" (with leading '=')
                    if (version.startsWith("=")) {
                        version = version.substring(1);
                    }
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

        // 3. Initialize platform adapter FIRST (registers modules + platform handlers)
        //    Direct call — cut reflection bridge #4. agent.jar bundles ForgeBootstrap,
        //    so we can import it directly. Exceptions no longer wrapped in
        //    InvocationTargetException — stack traces point to the real cause.
        if ("Forge".equals(platform)) {
            try {
                ForgeBootstrap.INSTANCE.init();
                log("[Agent] ForgeBootstrap.init() complete");
                forgeBootstrapAvailable = true;
            } catch (Throwable e) {
                log("[Agent] ForgeBootstrap init failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
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
        }

        // 4. Initialize adapter layer (AgentBridge — only for non-Forge platforms)
        //    Cut reflection bridge #5. Forge path: ForgeBootstrap already registered
        //    all 34 modules in step 3 — no need to call AgentBridge. Non-Forge paths
        //    (Fabric, future platforms) use AgentBridge as primary registration.
        if (!"Forge".equals(platform)) {
            try {
                String result = AgentBridge.initModules();
                log(result);
            } catch (Throwable e) {
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
        } else {
            log("[Agent] Forge platform — ForgeBootstrap already registered modules, skipping AgentBridge");
        }

        // 5-6. Install Transformer hook + start threads
        //      Wrapped in try-catch(Throwable) — P0 diagnostic fix.
        //      Previously, any exception here was silently swallowed by javaw.exe's
        //      missing stderr, leaving us blind. Now we log the full stack trace
        //      to switchlite-agent.log so the user can see exactly what failed.
        try {
            // 5. Install Transformer hook (Javassist's job, not Agent's)
            //
            // CRITICAL FIX: when inst == null (JNI bootstrap), do NOT touch the
            // Transformer class AT ALL. Loading Transformer from the game
            // ClassLoader (LaunchClassLoader) triggers JVM class verification,
            // which resolves the javassist.* references in transform() — and
            // LaunchClassLoader cannot see javassist in the fat jar, throwing
            // NoClassDefFoundError: javassist/ClassPath (confirmed in real logs).
            //
            // The hook is installed later by payload.dll via
            // agentmain("jni-attach", inst) — that callback runs on the
            // AppClassLoader (agent.jar on system CL via JPLIS), where javassist
            // IS visible. So in JNI mode we defer and let jni-attach do it.
            if (inst != null) {
                boolean hookInstalled = Transformer.install(inst);
                if (!hookInstalled) {
                    log("[Agent] FATAL: Transformer.install() failed even with Instrumentation — rendering pipeline broken.");
                } else {
                    log("[Agent] Render path: Transformer + RenderBridge → ForgeBootstrap.render() (every frame, stealthy)");
                }
            } else {
                log("[Agent] JNI mode — Transformer hook deferred to agentmain(jni-attach). Not loading Transformer from game CL (avoids javassist linkage error).");
            }

            // 6. Start threads (dispatch only — no rendering)
            // NOTE: key polling no longer consumes LWJGL's Keyboard.next() queue
            // — that raced MC's own KeyBinding input and caused input lag.
            // Keyboard STATE polling (isKeyDown, edge detection) lives in
            // ForgeBootstrap.render() on the render thread.
            startTickThread();

            log("[SwitchLite Agent] Ready — tick active");
        } catch (Throwable t) {
            log("[Agent] ============================================================");
            log("[Agent] FATAL: coreInit step 5-6 crashed: " + t.getClass().getName() + ": " + t.getMessage());
            log("[Agent] Stack trace:");
            for (StackTraceElement ste : t.getStackTrace()) {
                log("[Agent]   at " + ste.toString());
            }
            Throwable cause = t.getCause();
            while (cause != null) {
                log("[Agent]   Caused by: " + cause.getClass().getName() + ": " + cause.getMessage());
                for (StackTraceElement ste : cause.getStackTrace()) {
                    log("[Agent]     at " + ste.toString());
                }
                cause = cause.getCause();
            }
            log("[Agent] ============================================================");
            // Don't rethrow — let the agent continue running so jni-attach can still
            // attempt Transformer.install() later. The error is logged for diagnosis.
        }
    }

    // ═══════════════════════════════════════════
    //  ForgeBootstrap availability flag (no Method cache — direct calls)
    // ═══════════════════════════════════════════

    private static boolean forgeBootstrapAvailable = false;

    // ═══════════════════════════════════════════
    //  Tick Thread — dispatch only, no rendering
    // ═══════════════════════════════════════════

    private static Thread tickThread = null;

    /**
     * Runs at 20Hz. Dispatches tick events to ForgeBootstrap.tick() — direct call.
     */
    private static void startTickThread() {
        tickThread = new Thread(() -> {
            log("[TickThread] Started (20Hz tick dispatch)");
            while (running) {
                try {
                    if (forgeBootstrapAvailable) {
                        ForgeBootstrap.INSTANCE.tick();
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
    //  Key Polling — DISABLED (was consuming MC's key queue -> input lag)
    // ═══════════════════════════════════════════
    // The old KeyPoll thread drained LWJGL's Keyboard.next() event queue and
    // forwarded events to ForgeBootstrap.onKey(). That raced Minecraft's own
    // KeyBinding input loop (both consume the same queue) — MC lost key
    // events, causing input lag and "press many times to trigger" for the
    // user's keybinds (R, movement keys).
    //
    // Key handling now follows the architecture: MC owns the keyboard. The
    // ClickGUI is a real GuiScreen (MC dispatches its keys). For in-game
    // module keybinds we poll Keyboard.isKeyDown() STATE (never the event
    // queue) with edge detection in ForgeBootstrap.render().
    //
    // startKeyPollThread() is intentionally NOT called (see coreInit step 6).
    // The methods below are kept only so the file still parses; they are dead.
    private static void startKeyPollThread() {
        // Disabled — do nothing. Keyboard.state polling lives in ForgeBootstrap.render().
    }

    /** Dead stub — kept only so callers parse; never invoked. */
    private static void pollKeysOnce() {
    }

    /** Dead stub — kept only so callers parse; never invoked. */
    private static void retryKeyPoll() {
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
