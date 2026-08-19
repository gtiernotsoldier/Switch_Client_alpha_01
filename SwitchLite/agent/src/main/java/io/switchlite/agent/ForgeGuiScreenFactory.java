package io.switchlite.agent;

import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtNewConstructor;
import javassist.LoaderClassPath;

/**
 * Generates a concrete GuiScreen subclass at runtime.
 *
 * Why: Minecraft's GuiScreen is ABSTRACT — it cannot be instantiated directly
 * (new GuiScreen() throws InstantiationException). To open our ClickGUI as a
 * real MC GuiScreen we need a concrete subclass. We generate a minimal empty
 * subclass via Javassist: it inherits all default GuiScreen behavior (MC
 * renders the dark screen background, ungrab mouse, ESC to close), and we
 * draw our panels on top via OverlayRenderer.
 *
 * ClassLoader: the generated class must be defined by the GAME ClassLoader
 * (LaunchClassLoader) so MC accepts it as a GuiScreen. We add that CL to the
 * ClassPool via LoaderClassPath so javassist can resolve GuiScreen, then
 * cc.toClass(gameCL) defines the subclass in the game CL.
 */
public class ForgeGuiScreenFactory {

    private static Object cachedScreen = null;

    /**
     * Create (once) and return a concrete GuiScreen instance.
     * @param gameCL the game ClassLoader (LaunchClassLoader) that can see
     *               net.minecraft.client.gui.GuiScreen
     */
    public static synchronized Object createGuiScreen(ClassLoader gameCL) {
        if (cachedScreen != null) return cachedScreen;
        try {
            ClassPool pool = new ClassPool();
            if (gameCL != null) {
                pool.appendClassPath(new LoaderClassPath(gameCL));
            }

            CtClass guiScreen = pool.get("net.minecraft.client.gui.GuiScreen");
            CtClass sub = pool.makeClass("io.switchlite.agent.SwitchGuiScreen", guiScreen);
            sub.addConstructor(CtNewConstructor.defaultConstructor(sub));
            sub.setModifiers(javassist.Modifier.PUBLIC);

            Class<?> clazz;
            if (gameCL != null) {
                clazz = sub.toClass(gameCL, null);
            } else {
                clazz = sub.toClass();
            }
            cachedScreen = clazz.getConstructor().newInstance();
            System.out.println("[ForgeGuiScreenFactory] Created concrete GuiScreen: " + clazz.getName());
            return cachedScreen;
        } catch (Exception e) {
            System.out.println("[ForgeGuiScreenFactory] FAILED: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return null;
        }
    }
}
