package io.doppel.agent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Pre-flight for the Freelook camera redirect.
 *
 * The redirect rewrites a game class on the class-loading path, where a broken edit is a client that
 * does not start and a wrong edit is a module that silently does nothing — and there is no game here to
 * try it on. So the transform is exercised offline, end to end:
 *
 *  1. a synthetic `pk` (the obfuscated Entity) with the rotation fields, and a synthetic `bfk` (the
 *     obfuscated EntityRenderer) whose method reads them, are generated;
 *  2. the real Transformer rewrites the reader class;
 *  3. the result is DEFINED AND EXECUTED — a broken stack map fails at definition, not at compile time;
 *  4. the value is shown to be the entity's own when the property is absent, and the published freelook
 *     angle when it is present.
 *
 * It proves the bytecode is sound and does what the design says. It cannot prove the real classes are
 * reached in a real client — but if they are not, the install log now says so per class.
 */
class FreelookRedirectTransformTest {

    private static final String ENTITY = "net/minecraft/entity/Entity";
    private static final String RENDERER = "bfk";

    @AfterEach
    void clearProperties() {
        System.clearProperty("doppel.freelookYaw");
        System.clearProperty("doppel.freelookPitch");
    }

    @Test
    void theCameraRotationReadsBecomeTheFreelookAngles() throws Exception {
        ByteLoader loader = new ByteLoader();
        Class<?> entityClass = loader.define(ENTITY, syntheticEntity());
        byte[] transformed = new Transformer().transform(loader, RENDERER, null, null, syntheticRenderer());
        assertNotNull(transformed, "the renderer must be rewritten, not left alone");

        Class<?> renderer = loader.define(RENDERER, transformed);
        Object instance = renderer.getDeclaredConstructor().newInstance();
        Object entity = entityClass.getDeclaredConstructor().newInstance();
        entityClass.getField("y").setFloat(entity, 30f);
        entityClass.getField("z").setFloat(entity, 10f);
        Method readYaw = renderer.getDeclaredMethod("readYaw", entityClass);
        Method readPitch = renderer.getDeclaredMethod("readPitch", entityClass);

        assertEquals(30f, (Float) readYaw.invoke(instance, entity), 0.001f, "no property: the entity's own yaw");
        assertEquals(10f, (Float) readPitch.invoke(instance, entity), 0.001f, "no property: the entity's own pitch");

        System.setProperty("doppel.freelookYaw", "123.5");
        System.setProperty("doppel.freelookPitch", "-40.25");
        assertEquals(123.5f, (Float) readYaw.invoke(instance, entity), 0.001f, "freelook yaw takes over");
        assertEquals(-40.25f, (Float) readPitch.invoke(instance, entity), 0.001f, "freelook pitch takes over");

        System.clearProperty("doppel.freelookYaw");
        System.clearProperty("doppel.freelookPitch");
        assertEquals(30f, (Float) readYaw.invoke(instance, entity), 0.001f, "cleared: vanilla again");
    }

    /** `pk`: the obfuscated Entity, with the rotation fields the camera reads. */
    private static byte[] syntheticEntity() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, ENTITY, null, "java/lang/Object", null);
        cw.visitField(Opcodes.ACC_PUBLIC, "y", "F", null, null).visitEnd();
        cw.visitField(Opcodes.ACC_PUBLIC, "z", "F", null, null).visitEnd();
        MethodVisitor ctor = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        ctor.visitInsn(Opcodes.RETURN);
        ctor.visitMaxs(0, 0);
        ctor.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** `bfk`: a class whose methods read those fields, the way orientCamera does. */
    private static byte[] syntheticRenderer() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, RENDERER, null, "java/lang/Object", null);
        MethodVisitor ctor = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        ctor.visitInsn(Opcodes.RETURN);
        ctor.visitMaxs(0, 0);
        ctor.visitEnd();
        for (String[] m : new String[][] { { "readYaw", "y" }, { "readPitch", "z" } }) {
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, m[0], "(L" + ENTITY + ";)F", null, null);
            mv.visitCode();
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            mv.visitFieldInsn(Opcodes.GETFIELD, ENTITY, m[1], "F");
            mv.visitInsn(Opcodes.FRETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Defines bytes under their own name without the parent loader answering first. */
    private static final class ByteLoader extends ClassLoader {
        Class<?> define(String internalName, byte[] bytes) {
            return defineClass(internalName.replace('/', '.'), bytes, 0, bytes.length);
        }
    }
}
