package io.doppel.agent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pre-flight for the NoCameraBob bytecode hook.
 *
 * The hook rewrites a game class on the class-loading path, where a mistake would be a client that
 * does not start — and there is no game on the build machine to try it on. So the transform is
 * exercised here instead, end to end and offline:
 *
 *  1. a synthetic class with the same name and the same `setupViewBobbing(float)` shape is generated;
 *  2. it goes through the real Transformer;
 *  3. the result is **defined and executed** — which is what proves the frames verify, since a broken
 *     StackMapTable fails at class definition, not at compile time;
 *  4. the method body is shown to run normally with no property set, and to be skipped when
 *     `doppel.skipViewBobbing=true`.
 *
 * It does not prove the real EntityRenderer is hooked in a real client (that needs the game); it proves
 * the rewrite is structurally sound, which is the part that could take the client down.
 */
class BobHookTransformTest {

    private static final String OWNER = "net/minecraft/client/renderer/EntityRenderer";

    @AfterEach
    void clearProperty() {
        System.clearProperty("doppel.skipViewBobbing");
    }

    @Test
    void theBobHookTransformsVerifiesAndIsDrivenByTheProperty() throws Exception {
        byte[] original = syntheticEntityRenderer();
        Transformer transformer = new Transformer();

        byte[] transformed = transformer.transform(
                getClass().getClassLoader(), OWNER, null, null, original);
        assertNotNull(transformed, "the transform must produce bytes, not null");

        // Sanity: the hook really is in the output.
        final boolean[] sawCall = { false };
        new ClassReader(transformed).accept(new ClassVisitor(Opcodes.ASM4) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (!"setupViewBobbing".equals(name)) return mv;
                return new MethodVisitor(Opcodes.ASM4, mv) {
                    @Override
                    public void visitLdcInsn(Object value) {
                        if ("skipViewBobbing".equals(value)) sawCall[0] = true;
                        super.visitLdcInsn(value);
                    }
                };
            }
        }, 0);
        assertTrue(sawCall[0], "the injected call must be present in setupViewBobbing");

        // Define it — a verify error would be thrown right here.
        Class<?> rewritten = new ByteLoader().define(OWNER, transformed);
        Object instance = rewritten.getDeclaredConstructor().newInstance();
        Method bob = rewritten.getDeclaredMethod("setupViewBobbing", float.class);
        bob.setAccessible(true);
        Field calls = rewritten.getDeclaredField("calls");

        System.clearProperty("doppel.skipViewBobbing");
        bob.invoke(instance, 0f);
        assertEquals(1, calls.getInt(instance), "without the property the vanilla body must run");

        System.setProperty("doppel.skipViewBobbing", "true");
        bob.invoke(instance, 0f);
        assertEquals(1, calls.getInt(instance), "with the property set the body must be skipped");

        System.setProperty("doppel.skipViewBobbing", "false");
        bob.invoke(instance, 0f);
        assertEquals(2, calls.getInt(instance), "and false must restore the vanilla behaviour");
    }

    @Test
    void aClassWithoutTheMethodIsLeftAlone() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, OWNER, null, "java/lang/Object", null);
        cw.visitEnd();
        Transformer transformer = new Transformer();
        assertEquals(null, transformer.transform(getClass().getClassLoader(), OWNER, null, null, cw.toByteArray()),
                "no setupViewBobbing means no rewrite (and no half-injected class)");
    }

    /** A class that looks like EntityRenderer for the purposes of the hook. */
    private static byte[] syntheticEntityRenderer() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, OWNER, null, "java/lang/Object", null);
        cw.visitField(Opcodes.ACC_PUBLIC, "calls", "I", null, null).visitEnd();

        MethodVisitor ctor = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        ctor.visitInsn(Opcodes.RETURN);
        ctor.visitMaxs(0, 0);
        ctor.visitEnd();

        MethodVisitor m = cw.visitMethod(Opcodes.ACC_PRIVATE, "setupViewBobbing", "(F)V", null, null);
        m.visitCode();
        m.visitVarInsn(Opcodes.ALOAD, 0);
        m.visitInsn(Opcodes.DUP);
        m.visitFieldInsn(Opcodes.GETFIELD, OWNER, "calls", "I");
        m.visitInsn(Opcodes.ICONST_1);
        m.visitInsn(Opcodes.IADD);
        m.visitFieldInsn(Opcodes.PUTFIELD, OWNER, "calls", "I");
        m.visitInsn(Opcodes.RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Defines the rewritten bytes without letting the parent loader answer first. */
    private static final class ByteLoader extends ClassLoader {
        Class<?> define(String internalName, byte[] bytes) {
            // defineClass wants the binary name (dots); the internal name has slashes.
            return defineClass(internalName.replace('/', '.'), bytes, 0, bytes.length);
        }
    }
}
