package io.doppel.agent;

import io.doppel.adapter.common.mapping.MappingContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end pre-flight for the Fabric packet hooks (the ASM replacements for the two
 * deleted SpongePowered mixins).
 *
 * The transform rewrites a game class on the class-loading path, where a broken edit is a
 * client that does not start — and there is no Fabric client in CI. So, following the
 * FreelookRedirectTransformTest convention, the whole chain is exercised offline:
 *
 *  1. a mapping table carrying exactly the two new fabric hook keys is stored into
 *     MappingContext (the same shape MappingLoader hands it);
 *  2. synthetic stand-ins for net.minecraft.class_8673 (sendPacket) and
 *     net.minecraft.class_2743 (apply) are generated — with a BRANCH in the target method
 *     so the original bytecode carries its own stack map frame that lists only [this, arg]
 *     while the hook's temp slot 2 is live: that is the exact frame-shape risk the manual
 *     F_NEW emission has to survive;
 *  3. the real Transformer rewrites them through the fabric branch of transform();
 *  4. the results are DEFINED AND EXECUTED — a broken stack map fails at definition, not
 *     at compile time, and the fail-open/cancel directions of the bridge become runnable
 *     assertions:
 *       - no handler installed  → decision FALSE → original body runs (packet passes);
 *       - handler answers true  → hook RETURNs early → original body never runs (cancel);
 *       - handler throws        → bridge collapses to FALSE → original body runs again.
 *
 * The test runs on a modern JDK while the agent compiles to Java 8, so the source stays
 * Java-8-compatible (no var, no List.of, no switch expressions).
 */
public class TransformerFabricHookTest {

    private static final String C2S_CLASS = "net/minecraft/class_8673";
    private static final String C2S_METHOD = "method_52787";
    private static final String C2S_DESCRIPTOR = "(Lnet/minecraft/class_2596;)V";

    private static final String S2C_CLASS = "net/minecraft/class_2743";
    private static final String S2C_METHOD = "method_11817";
    private static final String S2C_DESCRIPTOR = "(Lnet/minecraft/class_2602;)V";

    // ── the two new fabric table entries, in MappingContext's consumed shape ──

    private static Map<String, Object> hookTable() {
        Map<String, Object> c2sEntry = new HashMap<String, Object>();
        c2sEntry.put("class", "net.minecraft.class_8673");
        c2sEntry.put("method", C2S_METHOD);
        c2sEntry.put("aliases", java.util.Arrays.asList(
                "clientCommonNetworkHandler_sendPacket", "sendPacket"));
        Map<String, Object> s2cEntry = new HashMap<String, Object>();
        s2cEntry.put("class", "net.minecraft.class_2743");
        s2cEntry.put("method", S2C_METHOD);
        s2cEntry.put("aliases", java.util.Arrays.asList(
                "entityVelocityUpdateS2CPacket_apply", "apply"));
        Map<String, Object> table = new HashMap<String, Object>();
        table.put("fabric:clientCommonNetworkHandler_sendPacket", c2sEntry);
        table.put("fabric:entityVelocityUpdateS2CPacket_apply", s2cEntry);
        return table;
    }

    @BeforeEach
    public void resetState() throws Exception {
        // Fresh lazy resolution + fresh mapping table + no bridge handler: each test
        // re-drives the full path from nothing.
        Transformer.resetFabricTargets();
        MappingContext.storeMappings(hookTable());
        for (String name : new String[] {
                "c2sHandler", "c2sOnOutbound", "s2cHandler", "s2cOnVelocityPacket" }) {
            Field f = PacketBridge.class.getDeclaredField(name);
            f.setAccessible(true);
            f.set(null, null);
        }
    }

    @Test
    public void c2sHookInjectsCancelsAndFailsOpen() throws Exception {
        byte[] transformed = new Transformer().transform(
                null, C2S_CLASS, null, null, fixture(C2S_CLASS, C2S_METHOD, C2S_DESCRIPTOR));
        assertNotNull(transformed, "transform() must rewrite the c2s target class");

        // The injected pattern: reflective Method.invoke, a RETURN that precedes the
        // original body, and F_NEW frames written by hand.
        MethodShape shape = readShape(transformed, C2S_METHOD, C2S_DESCRIPTOR);
        assertTrue(shape.invokeSeen,
                "the hook must call java/lang/reflect/Method.invoke reflectively");
        assertTrue(shape.fNewFrameSeen, "the hook must write F_NEW stack map frames");
        assertTrue(shape.returnBeforeOriginalBody >= 0,
                "a RETURN must be injected before the original body");
        assertTrue(shape.fieldAccesses.contains("calls"),
                "the ORIGINAL body (calls counter) must still be present after the hook");
        assertTrue(shape.ldcConstants.contains("io.doppel.agent.PacketBridge"),
                "the hook must look PacketBridge up reflectively");
        assertTrue(shape.ldcConstants.contains("onC2SPacket"),
                "the c2s hook must call PacketBridge.onC2SPacket");

        // Define and RUN: broken frames would fail right here.
        Class<?> cls = define(C2S_CLASS, transformed);
        Object instance = cls.getDeclaredConstructor().newInstance();
        // The parameter type is the packet stand-in, not Object — look the method up
        // through its real descriptor (the invoke below passes null either way).
        Class<?> pkt = cls.getClassLoader().loadClass("net.minecraft.class_2596");

        // No handler installed → bridge answers FALSE → fail-open, body runs.
        cls.getMethod(C2S_METHOD, pkt).invoke(instance, (Object) null);
        assertEquals(1, cls.getField("calls").getInt(null),
                "no decision object: the packet must pass through and the body must run");

        // Handler answers true → hook RETURNs early → body skipped (the vault hold).
        PacketBridge.install(new BooleanDecision("onOutbound", true), null);
        cls.getMethod(C2S_METHOD, pkt).invoke(instance, (Object) null);
        assertEquals(1, cls.getField("calls").getInt(null),
                "decision TRUE: the send must be cancelled before the original body");

        // Handler throws → bridge collapses to FALSE → fail-open again.
        PacketBridge.install(new ThrowingDecision("onOutbound"), null);
        cls.getMethod(C2S_METHOD, pkt).invoke(instance, (Object) null);
        // The fixture body counts +1 on its else branch and +2 on its then branch;
        // calls==1 here, so ONE further body run lands exactly at 3. Any lower value
        // would mean the throw swallowed the packet, any higher that it ran twice.
        assertEquals(3, cls.getField("calls").getInt(null),
                "a throwing decision object must never block the packet");
    }

    @Test
    public void s2cHookInjectsCancelsAndFailsOpen() throws Exception {
        byte[] transformed = new Transformer().transform(
                null, S2C_CLASS, null, null, fixture(S2C_CLASS, S2C_METHOD, S2C_DESCRIPTOR));
        assertNotNull(transformed, "transform() must rewrite the s2c target class");

        MethodShape shape = readShape(transformed, S2C_METHOD, S2C_DESCRIPTOR);
        assertTrue(shape.invokeSeen,
                "the hook must call java/lang/reflect/Method.invoke reflectively");
        assertTrue(shape.fNewFrameSeen, "the hook must write F_NEW stack map frames");
        assertTrue(shape.returnBeforeOriginalBody >= 0,
                "a RETURN must be injected before the original body");
        assertTrue(shape.fieldAccesses.contains("calls"),
                "the ORIGINAL body (calls counter) must still be present after the hook");
        assertTrue(shape.ldcConstants.contains("onVelocityPacket"),
                "the s2c hook must call PacketBridge.onVelocityPacket");

        Class<?> cls = define(S2C_CLASS, transformed);
        Object instance = cls.getDeclaredConstructor().newInstance();
        Class<?> listener = cls.getClassLoader().loadClass("net.minecraft.class_2602");

        // No handler installed → apply() proceeds (velocity update passes).
        cls.getMethod(S2C_METHOD, listener).invoke(instance, (Object) null);
        assertEquals(1, cls.getField("calls").getInt(null),
                "no decision object: the velocity packet must be applied normally");

        // Handler answers true → apply() never runs (the old listener-HEAD cancel).
        PacketBridge.install(null, new BooleanDecision("onVelocityPacket", true));
        cls.getMethod(S2C_METHOD, listener).invoke(instance, (Object) null);
        assertEquals(1, cls.getField("calls").getInt(null),
                "decision TRUE: the velocity packet must never be applied");
    }

    @Test
    public void targetClassWithoutTheMethodIsRejectedDefensively() throws Exception {
        // Same class name as the c2s target, but no method_52787 — the mapping rot case:
        // the visitor must find nothing and transform() must leave the class alone.
        byte[] noMethod = fixtureWithoutTargetMethod(C2S_CLASS);
        assertNull(new Transformer().transform(null, C2S_CLASS, null, null, noMethod),
                "a target-shaped class without the target method must not be rewritten");
    }

    @Test
    public void unrelatedClassIsIgnored() throws Exception {
        byte[] bytes = fixture(C2S_CLASS, C2S_METHOD, C2S_DESCRIPTOR);
        assertNull(new Transformer().transform(null, "net/minecraft/class_9999", null, null, bytes),
                "only the two resolved hook targets may ever be rewritten");
    }

    // ── fixture generation ───────────────────────────────────────────────────

    /**
     * A stand-in for a Fabric packet class: one counter method with the target name and
     * descriptor, whose body contains a BRANCH — so the original bytecode carries its own
     * stack map frame listing only [this, arg] while the hook's temp slot 2 is live. That
     * is the worst-case frame shape the manual F_NEW emission must survive; COMPUTE_FRAMES
     * on the fixture gives exactly that frame, and EXPAND_FRAMES in the transformer turns
     * it absolute on the way through.
     */
    private static byte[] fixture(String internalName, String methodName, String descriptor) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, internalName,
                null, "java/lang/Object", null);
        cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "calls", "I", null, null).visitEnd();

        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, methodName, descriptor, null, null);
        mv.visitCode();
        // if (calls != 0) calls += 2; else calls += 1;
        mv.visitFieldInsn(Opcodes.GETSTATIC, internalName, "calls", "I");
        Label elseLabel = new Label();
        Label end = new Label();
        mv.visitJumpInsn(Opcodes.IFNE, elseLabel);
        mv.visitFieldInsn(Opcodes.GETSTATIC, internalName, "calls", "I");
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitInsn(Opcodes.IADD);
        mv.visitFieldInsn(Opcodes.PUTSTATIC, internalName, "calls", "I");
        mv.visitJumpInsn(Opcodes.GOTO, end);
        mv.visitLabel(elseLabel);
        mv.visitFieldInsn(Opcodes.GETSTATIC, internalName, "calls", "I");
        mv.visitInsn(Opcodes.ICONST_2);
        mv.visitInsn(Opcodes.IADD);
        mv.visitFieldInsn(Opcodes.PUTSTATIC, internalName, "calls", "I");
        mv.visitLabel(end);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

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

    /** Same class name as the target, but carrying no method with the target's name. */
    private static byte[] fixtureWithoutTargetMethod(String internalName) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, internalName,
                null, "java/lang/Object", null);
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

    // ── decision-object stand-ins for the adapter side of PacketBridge ──

    /** Adapter decision object with the bridge contract: name(Object) → boolean. */
    public static final class BooleanDecision {
        private final String methodName;
        private final boolean answer;

        public BooleanDecision(String methodName, boolean answer) {
            this.methodName = methodName;
            this.answer = answer;
        }

        public boolean decide(Object packet) {
            return answer;
        }

        /** Exposed under the name PacketBridge resolves; chosen per half via reflection. */
        public boolean onOutbound(Object packet) {
            return methodName.equals("onOutbound") && decide(packet);
        }

        public boolean onVelocityPacket(Object packet) {
            return methodName.equals("onVelocityPacket") && decide(packet);
        }
    }

    /** Adapter decision object whose entry point always throws. */
    public static final class ThrowingDecision {
        private final String methodName;

        public ThrowingDecision(String methodName) {
            this.methodName = methodName;
        }

        public boolean onOutbound(Object packet) {
            if (!methodName.equals("onOutbound")) return false;
            throw new IllegalStateException("decision blew up");
        }

        public boolean onVelocityPacket(Object packet) {
            if (!methodName.equals("onVelocityPacket")) return false;
            throw new IllegalStateException("decision blew up");
        }
    }

    // ── transformed-byte inspection ──────────────────────────────────────────

    /** Everything the injected-pattern assertions need, from one pass over the bytes. */
    private static final class MethodShape {
        final List<Integer> opcodes = new ArrayList<Integer>();
        final List<String> fieldAccesses = new ArrayList<String>();
        final List<String> ldcConstants = new ArrayList<String>();
        boolean invokeSeen = false;
        boolean fNewFrameSeen = false;
        /** index of a RETURN whose next instruction is the original body's first opcode */
        int returnBeforeOriginalBody = -1;
    }

    private static MethodShape readShape(byte[] bytes, final String methodName, final String descriptor) {
        final MethodShape shape = new MethodShape();
        final int originalFirstOpcode = Opcodes.GETSTATIC;
        // EXPAND_FRAMES on purpose: the writer compresses the F_NEW frames we hand it into
        // the compact StackMapTable forms (F_SAME/F_APPEND/...), so only the expanded re-read
        // shows every frame as the F_NEW the injector wrote. Same lens the transformer uses.
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                if (!methodName.equals(name) || !descriptor.equals(desc)) return null;
                return new MethodVisitor(Opcodes.ASM9) {
                    private void record(int opcode) {
                        shape.opcodes.add(opcode);
                    }

                    @Override
                    public void visitInsn(int opcode) {
                        record(opcode);
                    }

                    @Override
                    public void visitVarInsn(int opcode, int var) {
                        record(opcode);
                    }

                    @Override
                    public void visitIntInsn(int opcode, int operand) {
                        record(opcode);
                    }

                    @Override
                    public void visitTypeInsn(int opcode, String type) {
                        record(opcode);
                    }

                    @Override
                    public void visitFieldInsn(int opcode, String owner, String name, String desc) {
                        record(opcode);
                        shape.fieldAccesses.add(name);
                    }

                    @Override
                    public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean itf) {
                        record(opcode);
                        if (opcode == Opcodes.INVOKEVIRTUAL
                                && "java/lang/reflect/Method".equals(owner)
                                && "invoke".equals(name)) {
                            shape.invokeSeen = true;
                        }
                    }

                    @Override
                    public void visitJumpInsn(int opcode, Label label) {
                        record(opcode);
                    }

                    @Override
                    public void visitLdcInsn(Object value) {
                        if (value instanceof String) {
                            shape.ldcConstants.add((String) value);
                        }
                    }

                    @Override
                    public void visitFrame(int type, int numLocal, Object[] local, int numStack, Object[] stack) {
                        if (type == Opcodes.F_NEW) {
                            shape.fNewFrameSeen = true;
                        }
                    }

                    @Override
                    public void visitEnd() {
                        // A RETURN that happens BEFORE the original body's first opcode:
                        // the cancel path must exit the method ahead of the body. The
                        // handler's POP may sit between them (it is entered only from the
                        // exception table, never by fall-through), so this is a
                        // before-check, not an adjacency check — the RUNTIME assertions
                        // below (define + invoke with decision TRUE, calls stays put) are
                        // what actually proves the early exit.
                        int firstBody = -1;
                        for (int i = 0; i < shape.opcodes.size(); i++) {
                            if (shape.opcodes.get(i) == originalFirstOpcode) { firstBody = i; break; }
                        }
                        for (int i = 0; firstBody >= 0 && i < firstBody; i++) {
                            if (shape.opcodes.get(i) == Opcodes.RETURN) {
                                shape.returnBeforeOriginalBody = i;
                                break;
                            }
                        }
                    }
                };
            }
        }, ClassReader.EXPAND_FRAMES);
        return shape;
    }

    /** Defines bytes under their own name without the parent loader answering first. */
    private static Class<?> define(final String internalName, byte[] bytes) throws Exception {
        ClassLoader loader = new ClassLoader(null) {
            private final Map<String, Class<?>> defined = new HashMap<String, Class<?>>();

            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                Class<?> hit = defined.get(name);
                if (hit != null) return hit;
                byte[] code;
                if (internalName.replace('/', '.').equals(name)) {
                    code = bytes;
                } else {
                    // Companion types the transformed frames and the method descriptor
                    // reference — the packet/listener stand-ins. The verifier loads them
                    // while checking the injected stack maps, so a loader that cannot
                    // serve them would mask the very check this test exists to run.
                    code = emptyClass(name);
                }
                Class<?> c = defineClass(name, code, 0, code.length);
                defined.put(name, c);
                return c;
            }
        };
        return Class.forName(internalName.replace('/', '.'), true, loader);
    }

    /** A minimal empty class — enough for the verifier to resolve the type. */
    private static byte[] emptyClass(String dottedName) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, dottedName.replace('.', '/'),
                null, "java/lang/Object", null);
        cw.visitEnd();
        return cw.toByteArray();
    }
}
