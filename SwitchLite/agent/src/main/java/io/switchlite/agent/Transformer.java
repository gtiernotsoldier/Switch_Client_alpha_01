package io.switchlite.agent;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtMethod;

/**
 * Class file transformer — injects RenderHook calls into LWJGL Display.update().
 *
 * When \u0040agentmain is called with "retransform-display", this transformer
 * patches org.lwjgl.opengl.Display.update():
 *
 *   public static void update() {
 *       RenderHook.preUpdate();          // ← injected
 *       // ... original body ...
 *       RenderHook.postUpdate();         // ← injected
 *   }
 *
 * This gives us a per-frame render hook that fires in the LWJGL thread,
 * AFTER the swap — ideal for 2D overlay rendering (HUD, ClickGUI, notifications).
 */
public class Transformer implements ClassFileTransformer {

    private static final String DISPLAY_CLASS = "org.lwjgl.opengl.Display";
    private static final String UPDATE_METHOD = "update";
    private static final String HOOK_CLASS = "io.switchlite.agent.RenderHook";

    @Override
    public byte[] transform(ClassLoader loader, String className,
                           Class<?> classBeingRedefined, ProtectionDomain protectionDomain,
                           byte[] classfileBuffer) {

        if (className == null) return null;

        // Convert internal name (org/lwjgl/opengl/Display) to dotted
        String dottedName = className.replace('/', '.');

        if (!DISPLAY_CLASS.equals(dottedName)) return null;

        return transformDisplayUpdate(classfileBuffer);
    }

    private byte[] transformDisplayUpdate(byte[] classfileBuffer) {
        try {
            ClassPool pool = ClassPool.getDefault();
            // Append our agent jar to classpath so Javassist can see RenderHook
            pool.appendClassPath(new javassist.ClassClassPath(RenderHook.class));

            CtClass cc = pool.makeClass(new java.io.ByteArrayInputStream(classfileBuffer));
            CtMethod updateMethod = cc.getDeclaredMethod(UPDATE_METHOD);

            // Inject: RenderHook.preUpdate(); at the start
            updateMethod.insertBefore("{ " + HOOK_CLASS + ".preUpdate(); }");

            // Inject: RenderHook.postUpdate(); at the end
            updateMethod.insertAfter("{ " + HOOK_CLASS + ".postUpdate(); }");

            byte[] result = cc.toBytecode();
            cc.detach();
            return result;
        } catch (Exception e) {
            System.err.println("[Transformer] Failed to transform Display.update(): " + e.getMessage());
            return null; // return null = don't transform, let class load normally
        }
    }
}
