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
     * Uses the thread context classloader to find ForgeBootstrap
     * (which is on the game classloader, not the bootstrap CL).
     *
     * ForgeBootstrap is a Kotlin object, so we need INSTANCE to call methods.
     */
    private static void initBridge() {
        try {
            ClassLoader gameCL = Thread.currentThread().getContextClassLoader();
            if (gameCL == null) return;

            Class<?> fbClass = Class.forName(
                "io.switchlite.adapter.forge.v1_8_9.ForgeBootstrap", true, gameCL);

            // Kotlin object: get INSTANCE field
            forgeBootstrapInstance = fbClass.getField("INSTANCE").get(null);
            forgeBootstrapRender = fbClass.getMethod("render");
            bridgeReady = true;

            Agent.log("[RenderBridge] Bridge established — ForgeBootstrap.render() will be called every frame");
        } catch (ClassNotFoundException e) {
            // ForgeBootstrap not loaded yet — will retry next frame
        } catch (Exception e) {
            Agent.log("[RenderBridge] Bridge init failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }
}
