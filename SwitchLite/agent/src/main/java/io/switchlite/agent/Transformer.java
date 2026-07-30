package io.switchlite.agent;

import java.io.ByteArrayInputStream;
import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtMethod;

/**
 * Class file transformer — hooks Display.update() for HUD rendering.
 *
 * Architecture: Single hook point.
 *   org.lwjgl.opengl.Display.update()
 *     -> insertBefore: RenderHook.onFrame()
 *     -> original update() (swap buffers)
 *
 * This runs every frame BEFORE the buffer swap.
 * GL context is current, MC has finished its render.
 * RenderHook.onFrame() handles all GL state save/restore.
 */
public class Transformer implements ClassFileTransformer {

    private static boolean hooked = false;

    @Override
    public byte[] transform(ClassLoader loader, String className,
                           Class<?> classBeingRedefined, ProtectionDomain protectionDomain,
                           byte[] classfileBuffer) {
        // Only hook LWJGL Display class
        if (!"org/lwjgl/opengl/Display".equals(className)) {
            return null;
        }
        if (hooked) return null; // already transformed

        try {
            ClassPool pool = ClassPool.getDefault();
            CtClass ctClass = pool.makeClass(new ByteArrayInputStream(classfileBuffer));

            // Find the update() method
            CtMethod updateMethod = ctClass.getDeclaredMethod("update");

            // Insert our render callback at the very beginning
            // Before: Display processes events + swaps buffers
            // After:  RenderHook.onFrame() -> Display processes events + swaps buffers
            updateMethod.insertBefore(
                "io.switchlite.agent.RenderHook.onFrame();"
            );

            byte[] result = ctClass.toBytecode();
            ctClass.defrost(); // release for potential re-transformation
            hooked = true;

            Agent.log("[Transformer] Hooked Display.update() for HUD rendering");
            return result;
        } catch (javassist.NotFoundException e) {
            Agent.log("[Transformer] Display.update() not found: " + e.getMessage());
        } catch (javassist.CannotCompileException e) {
            Agent.log("[Transformer] Compile error: " + e.getMessage());
        } catch (Exception e) {
            Agent.log("[Transformer] Failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        return null;
    }
}
