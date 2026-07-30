package io.switchlite.agent;

/**
 * RenderHook — injected into LWJGL Display.update() via retransformClasses.
 *
 * Called EVERY frame from the LWJGL thread, before and after Display.update().
 * Reads state from System properties (set by Agent HUD thread) instead of
 * direct volatile field access, because this class is loaded by the bootstrap
 * ClassLoader while Agent runs on the system ClassLoader.
 *
 * Only draws 2D overlay (HUD, ClickGUI panel, notifications).
 * 3D ESP rendering lives in ForgeBootstrap.render().
 */
public class RenderHook {

    private static final long DO_FRAME_INTERVAL_MS = 50;

    // ── Display.update() hook point ──

    /**
     * Called from Transformer-injected code BEFORE Display.update().
     * The injected bytecode does:
     *   RenderHook.preUpdate();
     *   (original Display.update() body)
     *   RenderHook.postUpdate();
     */
    public static void preUpdate() {
        // Reserve for future use (input capture, state snapshot)
    }

    /**
     * Called from Transformer-injected code AFTER Display.update().
     * This is where we draw the overlay — the swap has happened,
     * so we're rendering on top of the game frame.
     */
    public static void postUpdate() {
        doFrame();
    }

    // ── Frame logic ──

    private static long lastFrameTime = 0;

    private static void doFrame() {
        long now = System.currentTimeMillis();
        if (now - lastFrameTime < DO_FRAME_INTERVAL_MS) return;
        lastFrameTime = now;

        try {
            // Read state from System properties (cross-ClassLoader safe)
            String ht = System.getProperty("switchlite.hudText", "");
            boolean guiOpen = "true".equals(System.getProperty("switchlite.guiOpen", "false"));

            if (!ht.isEmpty()) {
                drawHudText(ht);
            }
            if (guiOpen) {
                drawClickGuiPanel();
            }

            // Draw queued notifications
            NotificationManager.renderAll();
        } catch (Exception e) {
            // Silently ignore — render errors shouldn't crash MC
        }
    }

    // ── HUD text rendering ──

    private static void drawHudText(String text) {
        try {
            // Use OpenGL reflection (same as ForgeBootstrap ReflectGL11)
            Class<?> gl11 = Class.forName("org.lwjgl.opengl.GL11");

            int GL_DEPTH_TEST = gl11.getField("GL_DEPTH_TEST").getInt(null);
            int GL_TEXTURE_2D = gl11.getField("GL_TEXTURE_2D").getInt(null);

            gl11.getMethod("glPushMatrix").invoke(null);
            gl11.getMethod("glDisable", int.class).invoke(null, GL_DEPTH_TEST);
            gl11.getMethod("glDisable", int.class).invoke(null, GL_TEXTURE_2D);

            // Draw background rectangle (top-left, semi-transparent)
            gl11.getMethod("glColor4f", float.class, float.class, float.class, float.class)
                .invoke(null, 0.0f, 0.0f, 0.0f, 0.4f);
            gl11.getMethod("glBegin", int.class).invoke(null, gl11.getField("GL_QUADS").getInt(null));
            gl11.getMethod("glVertex2f", float.class, float.class).invoke(null, 2f, 2f);
            gl11.getMethod("glVertex2f", float.class, float.class).invoke(null, 152f, 2f);
            gl11.getMethod("glVertex2f", float.class, float.class).invoke(null, 152f, 14f);
            gl11.getMethod("glVertex2f", float.class, float.class).invoke(null, 2f, 14f);
            gl11.getMethod("glEnd").invoke(null);

            // Use Minecraft FontRenderer to draw text (reflection)
            drawText(text, 4, 4);

            gl11.getMethod("glEnable", int.class).invoke(null, GL_DEPTH_TEST);
            gl11.getMethod("glEnable", int.class).invoke(null, GL_TEXTURE_2D);
            gl11.getMethod("glPopMatrix").invoke(null);
        } catch (Exception ignored) {}
    }

    private static void drawText(String text, int x, int y) {
        try {
            Class<?> mcClass = Class.forName("net.minecraft.client.Minecraft");
            java.lang.reflect.Method getMc = findStaticFactory(mcClass);
            if (getMc == null) return;
            Object mc = getMc.invoke(null);
            if (mc == null) return;

            // Minecraft.fontRendererObj (or equivalent)
            java.lang.reflect.Field frField = findFieldByType(mcClass, "FontRenderer");
            if (frField == null) return;
            Object fr = frField.get(mc);
            if (fr == null) return;

            // fontRenderer.drawStringWithShadow(text, x, y, color)
            fr.getClass().getMethod("drawStringWithShadow", String.class, int.class, int.class, int.class)
                .invoke(fr, text, x, y, 0xFFFFFF);
        } catch (Exception ignored) {}
    }

    // ── ClickGUI panel ──

    private static void drawClickGuiPanel() {
        try {
            Class<?> gl11 = Class.forName("org.lwjgl.opengl.GL11");
            int GL_QUADS = gl11.getField("GL_QUADS").getInt(null);

            // Panel background (center, semi-transparent)
            gl11.getMethod("glColor4f", float.class, float.class, float.class, float.class)
                .invoke(null, 0.1f, 0.1f, 0.15f, 0.85f);
            gl11.getMethod("glBegin", int.class).invoke(null, GL_QUADS);
            gl11.getMethod("glVertex2f", float.class, float.class).invoke(null, 200f, 50f);
            gl11.getMethod("glVertex2f", float.class, float.class).invoke(null, 500f, 50f);
            gl11.getMethod("glVertex2f", float.class, float.class).invoke(null, 500f, 400f);
            gl11.getMethod("glVertex2f", float.class, float.class).invoke(null, 200f, 400f);
            gl11.getMethod("glEnd").invoke(null);

            // Panel title bar
            gl11.getMethod("glColor4f", float.class, float.class, float.class, float.class)
                .invoke(null, 0.2f, 0.6f, 0.2f, 0.9f);
            gl11.getMethod("glBegin", int.class).invoke(null, GL_QUADS);
            gl11.getMethod("glVertex2f", float.class, float.class).invoke(null, 200f, 50f);
            gl11.getMethod("glVertex2f", float.class, float.class).invoke(null, 500f, 50f);
            gl11.getMethod("glVertex2f", float.class, float.class).invoke(null, 500f, 70f);
            gl11.getMethod("glVertex2f", float.class, float.class).invoke(null, 200f, 70f);
            gl11.getMethod("glEnd").invoke(null);

            drawText("SwitchLite v0.1.0-alpha", 208, 54);
        } catch (Exception ignored) {}
    }

    // ── Reflection helpers ──

    private static java.lang.reflect.Method findStaticFactory(Class<?> cls) {
        for (java.lang.reflect.Method m : cls.getDeclaredMethods()) {
            if (m.getParameterCount() == 0 && m.getReturnType() == cls &&
                java.lang.reflect.Modifier.isStatic(m.getModifiers())) return m;
        }
        return null;
    }

    private static java.lang.reflect.Field findFieldByType(Class<?> cls, String suffix) {
        for (java.lang.reflect.Field f : cls.getDeclaredFields()) {
            if (f.getType().getName().contains(suffix)) return f;
        }
        for (java.lang.reflect.Field f : cls.getFields()) {
            if (f.getType().getName().contains(suffix)) return f;
        }
        return null;
    }
}
