package io.doppel.agent;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the one rule that a silent regression already violated once.
 *
 * Instrumentation.retransformClasses must not add, remove or rename methods (JVMTI spec),
 * and Display is already loaded when the hook is installed. Emitting the hook as an extra
 * private method therefore got the whole transformation rejected while transform() had
 * already reported success: the log said "hooked", nothing was hooked, ForgeBootstrap
 * .render() never ran, and every module that depends on the render thread (key edges,
 * synthetic clicks) went dead while tick-only modules kept working.
 *
 * These tests run the real transformer over a stand-in for org.lwjgl.opengl.Display and
 * then load the result in a JVM, so a bad frame, a wrong max stack or a changed method
 * list fails the build instead of shipping.
 */
public class TransformerTest {

    /** The class name transform() acts on; anything else must be left alone. */
    private static final String TARGET = "org/lwjgl/opengl/Display";
    private static final String TARGET_DOTTED = "org.lwjgl.opengl.Display";

    @Test
    public void hookIsInlinedWithoutChangingTheMethodList() throws Exception {
        byte[] original = fixture(Opcodes.V1_8);
        List<String> before = methodNames(original);
        assertEquals(2, before.size(), "fixture should have exactly two methods");

        resetHookedFlag();
        byte[] transformed = new Transformer().transform(null, TARGET, null, null, original);
        assertNotNull(transformed, "transform() must rewrite the one class it targets");

        // 1. The assertion that would have caught the regression: retransformation cannot
        //    change the method list, so the injected hook must live inside update().
        assertEquals(before, methodNames(transformed),
                "the method list must be byte-for-byte unchanged: retransformClasses rejects added methods");

        // 2. The hook is actually there: the reflective lookup target must appear as a constant.
        assertTrue(ldcConstants(transformed).contains("io.doppel.agent.RenderBridge"),
                "the transformed class must carry the RenderBridge lookup");
        assertTrue(ldcConstants(transformed).contains("onFrame"),
                "the transformed class must carry the onFrame lookup");

        // 3. And the result still verifies and runs: any frame/maxs mistake surfaces here as
        //    a VerifyError or a wrong result rather than in someone's game.
        Class<?> cls = load(transformed);
        assertEquals(0, cls.getField("calls").getInt(null));
        cls.getMethod("update").invoke(null);
        assertEquals(1, cls.getField("calls").getInt(null), "the original body must still run");
        cls.getMethod("update").invoke(null);
        assertEquals(2, cls.getField("calls").getInt(null), "and keep running on every call");

        // 4. The other method must be untouched.
        cls.getMethod("updateTitle").invoke(null);
    }

    @Test
    public void oldClassFilesGetNoStackMapTableAndStillRun() throws Exception {
        // Display is Java 6 bytecode, but the transformer must not write frames into a class
        // file version that predates them (V1_5 = 49).
        byte[] transformed = transformFresh(fixture(Opcodes.V1_5));
        assertFalse(hasStackMapTable(transformed),
                "no StackMapTable may be written into a pre-Java-6 class file");
        Class<?> cls = load(transformed);
        cls.getMethod("update").invoke(null);
        assertEquals(1, cls.getField("calls").getInt(null));
    }

    @Test
    public void otherClassesAreIgnored() {
        resetHookedFlag();
        byte[] untouched = new Transformer().transform(null, "org/lwjgl/opengl/Other", null, null, fixture(Opcodes.V1_8));
        assertNull(untouched, "the transformer must only ever touch the class it targets");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** A stand-in for Display: one counter method plus a second method to prove it survives. */
    private static byte[] fixture(int classVersion) {
        // COMPUTE_MAXS, not COMPUTE_FRAMES: the fixture has no branches, so it needs no frames
        // of its own and every frame in the transformed class comes from the injection.
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(classVersion, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, TARGET, null, "java/lang/Object", null);
        cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "calls", "I", null, null).visitEnd();

        MethodVisitor update = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "update", "()V", null, null);
        update.visitCode();
        update.visitFieldInsn(Opcodes.GETSTATIC, TARGET, "calls", "I");
        update.visitInsn(Opcodes.ICONST_1);
        update.visitInsn(Opcodes.IADD);
        update.visitFieldInsn(Opcodes.PUTSTATIC, TARGET, "calls", "I");
        update.visitInsn(Opcodes.RETURN);
        update.visitMaxs(0, 0);
        update.visitEnd();

        MethodVisitor other = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "updateTitle", "()V", null, null);
        other.visitCode();
        other.visitInsn(Opcodes.RETURN);
        other.visitMaxs(0, 0);
        other.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] transformFresh(byte[] original) throws Exception {
        resetHookedFlag();
        byte[] transformed = new Transformer().transform(null, TARGET, null, null, original);
        assertNotNull(transformed);
        return transformed;
    }

    /** transform() latches a "already hooked" flag; each test needs a clean slate. */
    private static void resetHookedFlag() throws Exception {
        Field hooked = Transformer.class.getDeclaredField("hooked");
        hooked.setAccessible(true);
        hooked.setBoolean(null, false);
    }

    private static Class<?> load(byte[] bytes) throws Exception {
        ClassLoader loader = new ClassLoader(null) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                if (!TARGET_DOTTED.equals(name)) {
                    throw new ClassNotFoundException(name);
                }
                return defineClass(name, bytes, 0, bytes.length);
            }
        };
        return Class.forName(TARGET_DOTTED, true, loader);
    }

    private static List<String> methodNames(byte[] bytes) {
        final List<String> names = new ArrayList<String>();
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                names.add(name + descriptor);
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return names;
    }

    private static Set<String> ldcConstants(byte[] bytes) {
        final Set<String> found = new HashSet<String>();
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitLdcInsn(Object value) {
                        if (value instanceof String) {
                            found.add((String) value);
                        }
                    }
                };
            }
        }, 0);
        return found;
    }

    private static boolean hasStackMapTable(byte[] bytes) {
        final boolean[] seen = new boolean[] { false };
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitFrame(int type, int numLocal, Object[] local, int numStack, Object[] stack) {
                        seen[0] = true;
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG);
        return seen[0];
    }
}
