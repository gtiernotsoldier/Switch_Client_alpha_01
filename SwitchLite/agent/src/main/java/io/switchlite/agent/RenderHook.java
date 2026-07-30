package io.switchlite.agent;

import java.lang.reflect.Method;

/**
 * Bridge between Javassist-injected Display.update() and ForgeBootstrap.render().
 *
 * Architecture role: This is the Javassist render bridge.
 * - Called every frame from Display.update() BEFORE buffer swap
 * - GL context is current, MC has finished its render
 * - Delegates ALL rendering to ForgeBootstrap.render() via reflection
 * - Does NOT do any GL drawing itself — that's ForgeBootstrap's job
 *
 * Classloader note: RenderHook is on the bootstrap CL (after appendToBootstrapClassLoaderSearch).
 * ForgeBootstrap is on the game CL. We use Thread.currentThread().getContextClassLoader()
 * to bridge the classloader gap.
 */
public class RenderHook {

    private static volatile boolean bridgeReady = false;
    private static Object forgeBootstrapInstance = null;
    private static Method forgeBootstrapRender = null;
    private static int initAttempts = 0;

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
            // Only log first few failures
            if (initAttempts < 3) {
                initAttempts++;
                Agent.log("[RenderHook] render invoke failed: " + t.getClass().getSimpleName() + ": " + t.getMessage());
            }
        }
    }

    /**
     * Initialize the bridge to ForgeBootstrap.render().
     * Uses the thread context classloader to find ForgeBootstrap
     * (which is on the game classloader, not the bootstrap CL).
     *
     * ForgeBootstrap is a Kotlin object, so we need INSTANCE to call methods.
     */
    private static void initBridge() {
        try {
            // Use context classloader to bridge bootstrap CL → game CL
            ClassLoader gameCL = Thread.currentThread().getContextClassLoader();
            if (gameCL == null) return;

            Class<?> fbClass = Class.forName(
                "io.switchlite.adapter.forge.v1_8_9.ForgeBootstrap", true, gameCL);

            // Kotlin object: get INSTANCE field
            forgeBootstrapInstance = fbClass.getField("INSTANCE").get(null);
            forgeBootstrapRender = fbClass.getMethod("render");
            bridgeReady = true;

            Agent.log("[RenderHook] Bridge established — ForgeBootstrap.render() will be called every frame");
        } catch (ClassNotFoundException e) {
            // ForgeBootstrap not loaded yet — will retry next frame
        } catch (NoSuchFieldException e) {
            Agent.log("[RenderHook] ForgeBootstrap.INSTANCE not found: " + e.getMessage());
        } catch (NoSuchMethodException e) {
            Agent.log("[RenderHook] ForgeBootstrap.render() not found: " + e.getMessage());
        } catch (Exception e) {
            Agent.log("[RenderHook] Bridge init failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }
}
