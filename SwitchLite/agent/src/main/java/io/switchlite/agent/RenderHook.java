package io.switchlite.agent;

import java.lang.reflect.*;

/**
 * Direct OpenGL render callback for HUD/GUI overlay.
 * Hooked into Display.update() by Transformer — called every frame BEFORE buffer swap.
 *
 * Architecture: Independent of MC's render pipeline.
 * - GL11 for projection, state, rectangles (reflection, no compile dep)
 * - MC FontRenderer used ONLY as a glyph utility (reflection)
 * - All GL state saved/restored via glPushAttrib/glPopAttrib — zero impact on MC
 *
 * Works on BOTH main menu and in-game because Display.update()
 * is called every frame regardless of game state.
 */
public class RenderHook {

    // ── State (written by Agent from HudTick thread, read by MC render thread) ──
    public static volatile boolean guiVisible = false;
    public static volatile String hudText = "";

    // ── GL constants ──
    private static final int GL_ALL_ATTRIB_BITS     = 0xFFFFFFFF;
    private static final int GL_PROJECTION           = 0x1701;
    private static final int GL_MODELVIEW            = 0x1700;
    private static final int GL_DEPTH_TEST           = 0x0B71;
    private static final int GL_LIGHTING             = 0x0B50;
    private static final int GL_BLEND                = 0x0BE2;
    private static final int GL_SRC_ALPHA            = 0x0302;
    private static final int GL_ONE_MINUS_SRC_ALPHA  = 0x0303;
    private static final int GL_QUADS                = 0x0007;

    // ── Cached reflection handles ──
    private static volatile boolean ready = false;
    private static Method mcGet;
    private static Field mcFontRenderer;
    private static Method fontDraw;
    private static boolean fontUsesFloat = true;

    private static Method glPushAttrib, glPopAttrib;
    private static Method glMatrixMode, glPushMatrix, glPopMatrix;
    private static Method glLoadIdentity, glOrtho;
    private static Method glEnable, glDisable, glBlendFunc, glColor4f;
    private static Method glBegin, glEnd, glVertex2f;
    private static Method displayGetWidth, displayGetHeight, displayIsActive;

    // ScaledResolution — GUI coordinate scaling
    private static Constructor<?> srCtor;
    private static Method srGetWidth, srGetHeight;

    private static int frameCount = 0;
    private static final int LOG_EVERY_N_FRAMES = 300; // ~5s at 60fps

    /**
     * Called every frame from Display.update() before buffer swap.
     * GL context is current, MC has finished its render.
     * MUST NOT throw — catches everything internally.
     */
    public static void onFrame() {
        if (!ready) {
            initOnce();
            return;
        }
        try {
            doFrame();
        } catch (Throwable t) {
            if (frameCount < 3) {
                Agent.log("[HUD] " + t.getClass().getSimpleName() + ": " + t.getMessage());
            }
        }
        frameCount++;
    }

    private static void initOnce() {
        try {
            // ── GL11 static methods ──
            Class<?> gl11 = Class.forName("org.lwjgl.opengl.GL11");
            glPushAttrib = gl11.getMethod("glPushAttrib", int.class);
            glPopAttrib  = gl11.getMethod("glPopAttrib");
            glMatrixMode = gl11.getMethod("glMatrixMode", int.class);
            glPushMatrix = gl11.getMethod("glPushMatrix");
            glPopMatrix  = gl11.getMethod("glPopMatrix");
            glLoadIdentity = gl11.getMethod("glLoadIdentity");
            glOrtho     = gl11.getMethod("glOrtho",
                double.class, double.class, double.class, double.class, double.class, double.class);
            glEnable    = gl11.getMethod("glEnable", int.class);
            glDisable   = gl11.getMethod("glDisable", int.class);
            glBlendFunc = gl11.getMethod("glBlendFunc", int.class, int.class);
            glColor4f   = gl11.getMethod("glColor4f", float.class, float.class, float.class, float.class);
            glBegin     = gl11.getMethod("glBegin", int.class);
            glEnd       = gl11.getMethod("glEnd");
            glVertex2f  = gl11.getMethod("glVertex2f", float.class, float.class);

            // ── Display ──
            Class<?> displayClass = Class.forName("org.lwjgl.opengl.Display");
            displayGetWidth  = displayClass.getMethod("getWidth");
            displayGetHeight = displayClass.getMethod("getHeight");
            displayIsActive  = displayClass.getMethod("isActive");

            // ── Minecraft.getMinecraft() ──
            Class<?> mcClass = Class.forName("net.minecraft.client.Minecraft");
            for (String name : new String[]{"getMinecraft", "func_71410_x"}) {
                try { mcGet = mcClass.getMethod(name); break; } catch (Exception ignored) {}
            }

            // ── ScaledResolution for GUI coordinates ──
            try {
                Class<?> srClass = Class.forName("net.minecraft.client.gui.ScaledResolution");
                // MC 1.8.9: constructor takes (Minecraft) only
                try {
                    srCtor = srClass.getConstructor(mcClass);
                } catch (NoSuchMethodException e) {
                    // Newer MC: (Minecraft, int, int)
                    srCtor = srClass.getConstructor(mcClass, int.class, int.class);
                }
                srGetWidth  = srClass.getMethod("getScaledWidth");
                srGetHeight = srClass.getMethod("getScaledHeight");
            } catch (Exception e) {
                Agent.log("[HUD] ScaledResolution not found — raw pixel coords");
            }

            ready = true;
            Agent.log("[HUD] RenderHook ready (Display.update callback)");
        } catch (Throwable t) {
            Agent.log("[HUD] Init failed: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private static void doFrame() throws Exception {
        // Skip if minimized
        if (displayIsActive != null && !(Boolean) displayIsActive.invoke(null)) return;

        Object mc = mcGet.invoke(null);
        if (mc == null) return;

        int rawW = (Integer) displayGetWidth.invoke(null);
        int rawH = (Integer) displayGetHeight.invoke(null);
        if (rawW <= 0 || rawH <= 0) return;

        // ── Get FontRenderer (lazy init) ──
        if (mcFontRenderer == null) {
            Class<?> mcClass = mc.getClass();
            for (String fn : new String[]{"fontRendererObj", "field_71466_p"}) {
                try { mcFontRenderer = mcClass.getField(fn); break; } catch (Exception ignored) {}
            }
        }
        Object fr = (mcFontRenderer != null) ? mcFontRenderer.get(mc) : null;

        // ── Detect FontRenderer draw method (lazy, once) ──
        if (fr != null && fontDraw == null) {
            Class<?> frClass = fr.getClass();
            // Try float params first (MCP)
            for (String mn : new String[]{"drawStringWithShadow", "func_78266_a"}) {
                try {
                    fontDraw = frClass.getMethod(mn, String.class, float.class, float.class, int.class);
                    fontUsesFloat = true;
                    break;
                } catch (Exception ignored) {}
            }
            // Fallback: scan for (String, num, num, int) -> int
            if (fontDraw == null) {
                for (Method m : frClass.getMethods()) {
                    Class<?>[] p = m.getParameterTypes();
                    if (p.length == 4 && p[0] == String.class
                            && (p[1] == float.class || p[1] == int.class)
                            && (p[2] == float.class || p[2] == int.class)
                            && p[3] == int.class && m.getReturnType() == int.class) {
                        fontDraw = m;
                        fontUsesFloat = (p[1] == float.class);
                        Agent.log("[HUD] Font draw: " + m.getName() + " float=" + fontUsesFloat);
                        break;
                    }
                }
            }
            if (fontDraw != null) {
                Agent.log("[HUD] FontRenderer ready: " + fontDraw.getName());
            }
        }

        // ── Calculate GUI-scaled dimensions ──
        int guiW, guiH;
        if (srCtor != null) {
            Object sr;
            if (srCtor.getParameterTypes().length == 1) {
                sr = srCtor.newInstance(mc);
            } else {
                sr = srCtor.newInstance(mc, rawW, rawH);
            }
            guiW = (Integer) srGetWidth.invoke(sr);
            guiH = (Integer) srGetHeight.invoke(sr);
        } else {
            guiW = rawW;
            guiH = rawH;
        }

        // ══════════════════════════════════════
        //  GL State: Save
        // ══════════════════════════════════════
        glPushAttrib.invoke(null, GL_ALL_ATTRIB_BITS);
        glMatrixMode.invoke(null, GL_PROJECTION);
        glPushMatrix.invoke(null);
        glMatrixMode.invoke(null, GL_MODELVIEW);
        glPushMatrix.invoke(null);

        // ══════════════════════════════════════
        //  Setup 2D ortho (origin top-left, y-down)
        // ══════════════════════════════════════
        glMatrixMode.invoke(null, GL_PROJECTION);
        glLoadIdentity.invoke(null);
        glOrtho.invoke(null, 0.0, (double) guiW, (double) guiH, 0.0, -1.0, 1.0);
        glMatrixMode.invoke(null, GL_MODELVIEW);
        glLoadIdentity.invoke(null);

        // Disable 3D
        glDisable.invoke(null, GL_DEPTH_TEST);
        glDisable.invoke(null, GL_LIGHTING);
        glEnable.invoke(null, GL_BLEND);
        glBlendFunc.invoke(null, GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

        // ══════════════════════════════════════
        //  Draw HUD
        // ══════════════════════════════════════
        float y = 4.0f;
        float x = 4.0f;
        boolean hasFont = (fr != null && fontDraw != null);

        // -- Header --
        String header = guiVisible ? "[SwitchLite] GUI: ON" : "[SwitchLite] v0.1-alpha";
        float headerH = 12.0f;

        // -- Lines when GUI open --
        float totalH = headerH;
        if (guiVisible) totalH += 24.0f; // 2 extra lines
        String ht = hudText;
        if (ht != null && !ht.isEmpty()) totalH += 16.0f;

        // Background rect
        drawRect(x - 2, y - 2, 200, totalH + 4, 0.0f, 0.0f, 0.0f, 0.5f);

        // Header text
        if (hasFont) drawText(fr, header, x, y, 0xFFFF55);
        y += headerH;

        if (guiVisible) {
            if (hasFont) drawText(fr, "Right Shift = toggle", x, y, 0xAAAAAA);
            y += 12;
            if (hasFont) drawText(fr, "Modules active", x, y, 0x55FF55);
            y += 12;
        }

        if (ht != null && !ht.isEmpty()) {
            y += 4;
            if (hasFont) drawText(fr, ht, x, y, 0xFFFFFF);
        }

        // ══════════════════════════════════════
        //  GL State: Restore
        // ══════════════════════════════════════
        glMatrixMode.invoke(null, GL_PROJECTION);
        glPopMatrix.invoke(null);
        glMatrixMode.invoke(null, GL_MODELVIEW);
        glPopMatrix.invoke(null);
        glPopAttrib.invoke(null);

        // Periodic log
        if (frameCount % LOG_EVERY_N_FRAMES == 1) {
            Agent.log("[HUD] frame=" + frameCount + " gui=" + guiW + "x" + guiH);
        }
    }

    // ── Helpers ──

    private static void drawText(Object fr, String text, float x, float y, int color) throws Exception {
        if (fontUsesFloat) {
            fontDraw.invoke(fr, text, x, y, color);
        } else {
            fontDraw.invoke(fr, text, (int) x, (int) y, color);
        }
    }

    private static void drawRect(float x, float y, float w, float h,
                                  float r, float g, float b, float a) throws Exception {
        glColor4f.invoke(null, r, g, b, a);
        glBegin.invoke(null, GL_QUADS);
        glVertex2f.invoke(null, x, y);
        glVertex2f.invoke(null, x + w, y);
        glVertex2f.invoke(null, x + w, y + h);
        glVertex2f.invoke(null, x, y + h);
        glEnd.invoke(null);
        glColor4f.invoke(null, 1.0f, 1.0f, 1.0f, 1.0f);
    }
}
