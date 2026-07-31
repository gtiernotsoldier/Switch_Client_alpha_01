package io.switchlite.agent;

import java.lang.reflect.Method;

/**
 * ClassLoader bridge between Javassist-injected Display.update() and ForgeBootstrap.render().
 *
 * Architecture role: This is the Javassist render bridge.
 * - Called every frame from Display.update() BEFORE buffer swap
 * - GL context is current, MC has finished its render
 * - Delegates ALL rendering to ForgeBootstrap.render() via reflection
 * - Does NOT do any GL drawing itself — that's ForgeBootstrap's job
 *
 * Classloader note: RenderBridge is on the bootstrap CL (after appendToBootstrapClassLoaderSearch).
 * ForgeBootstrap is on the game CL. We use Thread.currentThread().getContextClassLoader()
 * to bridge the classloader gap.
 *
 * Why this bridge exists: Javassist injects bytecode into Display.update() which runs in
 * LWJGL's ClassLoader. ForgeBootstrap lives in the agent's ClassLoader. The injected code
 * can reference RenderBridge because it's on the bootstrap CL (visible to all).
 * RenderBridge then uses the context ClassLoader to find and call ForgeBootstrap.
 */
public class RenderBridge {

    private static volatile boolean bridgeReady = false;
    private static Object forgeBootstrapInstance = null;
    private static Method forgeBootstrapRender = null;

    /**
     * Called every frame from Display.update() before buffer swap.
     * GL context is current, MC has finished its render.
     * MUST NOT throw — catches everything internally.
     */
    public static void onFrame() {
        if (!bridgeReady) {
            initBridge();
            return;
        }
        try {
            if (forgeBootstrapInstance != null && forgeBootstrapRender != null) {
                forgeBootstrapRender.invoke(forgeBootstrapInstance);
            }
        } catch (Throwable t) {
            // Silently ignore — don't crash the game
        }
    }

    /**
     * Initialize the bridge to ForgeBootstrap.render().
     *
     * ClassLoader resolution strategy (in order of priority):
     *   1. Thread context ClassLoader (works if MC/LWJGL thread has the game CL set)
     *   2. Agent.class.getClassLoader() (the game CL — agent.jar is on the game CL)
     *   3. Forge LaunchClassLoader (Forge 1.8.x — the CL that loads MC classes)
     *
     * RenderBridge is on the bootstrap CL (via appendToBootstrapClassLoaderSearch),
     * so it cannot see ForgeBootstrap directly. We must find the game CL explicitly.
     *
     * ForgeBootstrap is a Kotlin object, so we need INSTANCE to call methods.
     */
    private static void initBridge() {
        ClassLoader gameCL = resolveGameClassLoader();
        if (gameCL == null) {
            initAttemptCount++;
            // Throttled logging — every 500 frames, plus a FATAL after 5000 attempts
            if (initAttemptCount == 5000) {
                log("[RenderBridge] FATAL: No game ClassLoader found after 5000 attempts — render pipeline permanently broken");
                log("[RenderBridge] This usually means the agent was loaded on the wrong ClassLoader");
                log("[RenderBridge] Check that agent.jar is on the game ClassLoader, not the bootstrap CL");
            } else if (initAttemptCount % 500 == 0) {
                log("[RenderBridge] No game ClassLoader found after " + initAttemptCount + " attempts");
            }
            return;
        }

        try {
            Class<?> fbClass = Class.forName(
                "io.switchlite.adapter.forge.v1_8_9.ForgeBootstrap", true, gameCL);

            // Kotlin object: get INSTANCE field
            forgeBootstrapInstance = fbClass.getField("INSTANCE").get(null);
            forgeBootstrapRender = fbClass.getMethod("render");
            bridgeReady = true;

            log("[RenderBridge] Bridge established — ForgeBootstrap.render() will be called every frame");
        } catch (ClassNotFoundException e) {
            // ForgeBootstrap not loaded yet — will retry next frame
            if (initAttemptCount++ % 500 == 0) {
                log("[RenderBridge] ForgeBootstrap not found yet (attempt " + initAttemptCount + ")");
            }
        } catch (Exception e) {
            log("[RenderBridge] Bridge init failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private static int initAttemptCount = 0;

    /**
     * Resolve the game ClassLoader using multiple strategies.
     *
     * Strategy 1: Thread context ClassLoader (works if MC/LWJGL thread has the game CL set)
     * Strategy 2: Agent.class.getClassLoader() (the game CL — agent.jar is on the game CL)
     * Strategy 3: Forge LaunchClassLoader (Forge 1.8.x — the CL that loads MC classes)
     *
     * @return the game ClassLoader, or null if not found
     */
    private static ClassLoader resolveGameClassLoader() {
        // Strategy 1: Thread context ClassLoader
        try {
            ClassLoader contextCL = Thread.currentThread().getContextClassLoader();
            if (contextCL != null) {
                // Verify it can see ForgeBootstrap
                try {
                    Class.forName("io.switchlite.adapter.forge.v1_8_9.ForgeBootstrap", false, contextCL);
                    return contextCL;
                } catch (ClassNotFoundException e) {
                    // Context CL doesn't have ForgeBootstrap — try next strategy
                }
            }
        } catch (Exception ignored) {}

        // Strategy 2: Agent.class.getClassLoader() (the game CL)
        try {
            ClassLoader agentCL = Agent.class.getClassLoader();
            if (agentCL != null) {
                try {
                    Class.forName("io.switchlite.adapter.forge.v1_8_9.ForgeBootstrap", false, agentCL);
                    return agentCL;
                } catch (ClassNotFoundException e) {
                    // Agent CL doesn't have ForgeBootstrap — try next strategy
                }
            }
        } catch (Exception ignored) {}

        // Strategy 3: Forge LaunchClassLoader (Forge 1.8.x)
        // NOTE: Must use an explicit classloader to find LaunchClassLoader because
        // RenderBridge is on the bootstrap CL — Class.forName() without a classloader
        // uses the caller's CL (bootstrap), which cannot see LaunchClassLoader.
        try {
            ClassLoader contextCL = Thread.currentThread().getContextClassLoader();
            if (contextCL != null) {
                Class<?> launchCLClass = Class.forName(
                    "net.minecraft.launchwrapper.LaunchClassLoader", false, contextCL);
                // Walk the parent chain of the context CL to find LaunchClassLoader
                ClassLoader cl = contextCL;
                while (cl != null) {
                    if (launchCLClass.isInstance(cl)) {
                        try {
                            Class.forName("io.switchlite.adapter.forge.v1_8_9.ForgeBootstrap", false, cl);
                            return cl;
                        } catch (ClassNotFoundException e) {
                            // This LaunchClassLoader doesn't have ForgeBootstrap
                        }
                    }
                    cl = cl.getParent();
                }
            }
        } catch (ClassNotFoundException ignored) {
            // Not Forge — LaunchClassLoader not available
        }

        return null;
    }

    /**
     * Local logging — does NOT use Agent.log() because RenderBridge is on the
     * bootstrap CL while Agent.log()'s logStream lives on the game CL's Agent.class.
     * Using Agent.log() from here would silently skip file logging (logStream == null
     * on the bootstrap CL's Agent.class). This method writes to stdout and the
     * shared payload log file directly.
     */
    private static void log(String msg) {
        System.out.println(msg);
        // Also try to write to the shared payload log file
        try {
            String tempDir = System.getProperty("java.io.tmpdir");
            if (tempDir != null) {
                java.io.File logFile = new java.io.File(tempDir, "switchlite-agent.log");
                java.io.PrintWriter pw = new java.io.PrintWriter(new java.io.FileWriter(logFile, true), true);
                pw.println("[" + new java.text.SimpleDateFormat("HH:mm:ss.SSS").format(new java.util.Date()) + "] " + msg);
                pw.close();
            }
        } catch (Exception ignored) {}
    }
}
