package io.doppel.agent;

import java.io.File;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.security.ProtectionDomain;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * ASM bytecode injection layer — hooks Display.update() for HUD rendering.
 *
 * Architecture role: This is the "stealthy" injection layer.
 * - Gets Instrumentation (via provided inst or self-attach)
 * - Injects RenderBridge.onFrame() call into Display.update()
 * - RenderBridge then delegates to ForgeBootstrap.render() on the MC render thread
 *
 * Stealth advantage: Bytecode injection into the method body is invisible to
 * task-queue scanning and thread-based detection. Anti-cheat that checks
 * addScheduledTask, reflection, or thread lists won't find it.
 *
 * Instrumentation acquisition strategies (in order of priority):
 *   1. Direct inst from premain/agentmain (always works if available)
 *   2. Self-attach via VirtualMachine (needs tools.jar, JDK only — FAILS on JRE)
 *   3. Windows Attach pipe protocol from payload.dll (JNI mode, no tools.jar needed)
 *      — payload.dll triggers agentmain("jni-attach", inst) which calls install(inst)
 *      — This is the primary path for DLL injection mode
 */
public class Transformer implements ClassFileTransformer {

    private static boolean hooked = false;
    private static volatile boolean installed = false;

    /** Whether the EntityRenderer.setupViewBobbing hook was installed (NoCameraBob). */
    private static volatile boolean bobHooked = false;

    /**
     * ASM api level to declare — deliberately NOT Opcodes.ASM9, and this is load-bearing.
     *
     * The agent jar is appended to the system ClassLoader search path by the Attach
     * mechanism, so any ASM already on the game's classpath wins the lookup. Forge 1.8.9
     * ships asm-all-5.0.3 AHEAD of agent.jar, so inside the game org.objectweb.asm.*
     * resolves to ASM 5.0.3 even though agent.jar bundles 9.7 — the bundled copy is dead
     * weight there, and the same thing will happen with any other host that ships its own
     * ASM.
     *
     * ASM validates the api argument in its visitor constructors and throws a bare
     * IllegalArgumentException() (null message) for an api level it does not recognise.
     * Opcodes.ASM9 is a compile-time constant, so it is inlined into this class as 589824;
     * ASM 5.0.3 rejects that, and retransformClasses therefore reported only
     * "IllegalArgumentException: null" and left update() untouched (field-verified).
     *
     * ASM4 is accepted by every ASM from 4 to 9 and is all this class needs: the visitor
     * calls below are ASM4-era (visitMethodInsn's 5-arg form exists since ASM 5.0, which is
     * the oldest runtime we can actually meet here).
     */
    private static final int COMPAT_API = Opcodes.ASM4;

    /**
     * Cached game ClassLoader — found once, reused for all Class.forName() calls.
     * When agentmain() is called by the JPLIS agent, it loads this Agent class
     * using the system CL (via appendToSystemClassLoaderSearch). The system CL
     * cannot see LWJGL classes. We need to find the game CL to load Display.
     */
    private static volatile ClassLoader cachedGameCL = null;

    /**
     * Whether the Display.update() hook was successfully installed.
     * Agent.java checks this to determine if the rendering pipeline is functional.
     * If false, Agent will log a fatal error and exit — no fallback.
     */
    public static boolean isInstalled() {
        return installed;
    }

    /**
     * Install the Display.update() hook.
     * Called by Agent.java during bootstrap.
     *
     * Strategy:
     * 1. If Instrumentation is available, use retransformClasses directly
     * 2. If not, try self-attach via VirtualMachine to get Instrumentation
     *
     * @param inst Instrumentation from premain/agentmain, or null in JNI mode
     * @return true if the hook was installed successfully
     */
    public static boolean install(Instrumentation inst) {
        // Strategy 1: Use provided Instrumentation
        if (inst != null) {
            try {
                // CRITICAL: Class.forName() uses the caller's ClassLoader.
                // When called from agentmain() via JPLIS, the caller (Transformer)
                // is on the system CL, which cannot see LWJGL classes.
                // We must use the game CL explicitly to find Display.
                Class<?> displayClass = findDisplayClass();
                Agent.log("[Transformer] Display class found via: " + displayClass.getClassLoader());
                if (inst.isModifiableClass(displayClass)) {
                    // NOTE: keep agent.jar OFF the bootstrap ClassLoader. The hook itself
                    // is a self-call inside Display (always resolvable) and RenderBridge is
                    // looked up through the render thread's context CL (LaunchClassLoader,
                    // which sees agent.jar via payload.dll addURL), so no bootstrap
                    // visibility is needed anywhere.
                    inst.addTransformer(new Transformer(), true);
                    inst.retransformClasses(displayClass);

                    // CRITICAL: retransformClasses() does NOT throw if transform() returns null.
                    // We must check the 'hooked' flag to know if the bytecode was actually modified.
                    // Second hook: EntityRenderer.setupViewBobbing (NoCameraBob). Best-effort — if the
                    // class is not loaded yet the transformer picks it up on load, and if this fails the
                    // module simply does nothing.
                    try {
                        Class<?> erClass = findEntityRendererClass();
                        if (erClass != null && inst.isModifiableClass(erClass)) {
                            inst.retransformClasses(erClass);
                            Agent.log("[Transformer] EntityRenderer retransform requested; bob hook = " + bobHooked);
                        } else {
                            Agent.log("[Transformer] EntityRenderer not loaded yet — the transformer will catch it on load");
                        }
                    } catch (Throwable erEx) {
                        Agent.log("[Transformer] EntityRenderer retransform failed (not fatal): " + erEx);
                    }

                    if (hooked) {
                        Agent.log("[Transformer] Display.update() hooked via Instrumentation.retransformClasses");
                        installed = true;
                        return true;
                    } else {
                        Agent.log("[Transformer] FATAL: retransformClasses completed but transform() returned null — bytecode was NOT modified");
                        Agent.log("[Transformer] This means Display.update() (()V) was not found in the class we transformed");
                        return false;
                    }
                }
            } catch (Exception e) {
                Agent.log("[Transformer] Retransform failed: " + e.getMessage() + " — trying self-attach");
            }
        }

        // Strategy 2: Self-attach via VirtualMachine
        try {
            ensureToolsJar();
            String pid = getProcessPid();
            if (pid != null) {
                Agent.log("[Transformer] Trying self-attach for PID " + pid + "...");
                Class<?> vmClass = Class.forName("com.sun.tools.attach.VirtualMachine");
                Method attachMethod = vmClass.getMethod("attach", String.class);
                Object vm = attachMethod.invoke(null, pid);

                String agentJarPath = findAgentJar();
                if (agentJarPath != null) {
                    Method loadAgentMethod = vmClass.getMethod("loadAgent", String.class, String.class);
                    loadAgentMethod.invoke(vm, agentJarPath, "retransform-display");
                    Agent.log("[Transformer] Self-attach succeeded — agentmain will handle retransform");
                    Method detachMethod = vmClass.getMethod("detach");
                    detachMethod.invoke(vm);
                    installed = true;
                    return true;
                } else {
                    Agent.log("[Transformer] agent.jar not found for self-attach");
                }
            }
        } catch (Exception e) {
            Agent.log("[Transformer] Self-attach failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        Agent.log("[Transformer] FATAL: Could not install Display.update() hook — no fallback available");
        return false;
    }

    /**
     * Called by agentmain when self-attach succeeds with "retransform-display" arg.
     * This is the callback from the self-attach path.
     */
    public static void handleRetransform(Instrumentation inst) {
        try {
            // See install(): agent.jar is already on the system CL — do NOT append
            // it to the bootstrap CL (that would put the bytecode library on a second
            // ClassLoader and re-create the old linker conflict).
            inst.addTransformer(new Transformer(), true);
            Class<?> displayClass = findDisplayClass();
            Agent.log("[Transformer] handleRetransform: Display class found via: " + displayClass.getClassLoader());
            inst.retransformClasses(displayClass);

            // Same false-positive fix as install() — check hooked flag
            if (hooked) {
                Agent.log("[Transformer] Display.update() hooked via self-attach retransform");
                installed = true;
            } else {
                Agent.log("[Transformer] handleRetransform: retransformClasses completed but transform() returned null");
            }
        } catch (Throwable e) {
            Agent.log("[Transformer] handleRetransform failed: " + e.getClass().getName() + ": " + e.getMessage());
        }
    }

    @Override
    public byte[] transform(ClassLoader loader, String className,
                           Class<?> classBeingRedefined, ProtectionDomain protectionDomain,
                           byte[] classfileBuffer) {
        // NoCameraBob: skip the camera view-bob. The class reaches us under its deobfuscated name once
        // FML's remapper has run, and under its obfuscated name if it has not — accept both spellings.
        if ("net/minecraft/client/renderer/EntityRenderer".equals(className) || "bfk".equals(className)) {
            if (bobHooked) return null;
            return transformEntityRenderer(classfileBuffer);
        }

        // Handle the HUD render hook (Display) only.
        if (!"org/lwjgl/opengl/Display".equals(className)) {
            return null;
        }
        if (hooked) return null;
        return transformDisplay(classfileBuffer);
    }

    /**
     * Inject RenderBridge.onFrame() into Display.update() (HUD overlay hook).
     * KeepSprint lives in the module layer (adapter/common KeepSprint.onRenderFrame) — no
     * attack-method injection here.
     */
    private byte[] transformDisplay(byte[] classfileBuffer) {
        try {
            ClassReader reader = new ClassReader(classfileBuffer);

            // Diagnostic: which CL loaded Transformer (and therefore ASM) — it decides
            // which classes we can reference from the code that runs here.
            ClassLoader transformerCL = Transformer.class.getClassLoader();
            Agent.log("[Transformer] Transformer classloader: " + (transformerCL != null ? transformerCL.getClass().getName() : "bootstrap (null)"));
            Agent.log("[Transformer] ASM ClassReader loaded by: " + ClassReader.class.getClassLoader());

            // The hook is emitted as a NEW private static method, and update() only gets a
            // single self-INVOKESTATIC. Two reasons:
            //   1. a self-call can never hit the cross-ClassLoader resolution problem that
            //      the reflective RenderBridge lookup inside the hook exists to dodge;
            //   2. inserting a try/catch directly into update() would make the original
            //      method body a jump target, which forces StackMapTable frames to be
            //      recomputed for the whole method — and re-deriving frames for a game
            //      class means resolving game types, which this ClassLoader cannot do.
            // The hook body carries its own frames (we know their exact shape), so the
            // original method's frames are left untouched.
            // The hook must be inserted INSIDE update()'s own body, never as a new method.
            // Instrumentation.retransformClasses is not allowed to add, remove or rename
            // methods (JVMTI spec), and Display is already loaded — so emitting the hook as a
            // helper method gets the whole transformation rejected AFTER transform() has
            // already set its success flag. That silent failure was a real regression: the
            // hook never ran, so ForgeBootstrap.render() never ran, so every module that
            // depends on the render thread (key edges, synthetic clicks) went dead while
            // tick-only modules kept working.
            //
            // Inserting a try/catch at method entry makes the original body a jump target, so
            // its frames have to be known. They are: the entry locals are fixed by the
            // method's own descriptor, the handler sees exactly one value (the Throwable) and
            // the continuation sees an empty stack. So the frames are written out instead of
            // computed — and computing them is not an option anyway, because that would
            // require resolving game types this ClassLoader cannot see.
            final boolean needsFrames = (reader.readShort(6) & 0xffff) >= 50;
            final boolean[] injected = new boolean[] { false };
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
            ClassVisitor injector = new ClassVisitor(COMPAT_API, writer) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                    MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                    if (mv == null) return null;
                    if (!injected[0] && "update".equals(name) && "()V".equals(descriptor)) {
                        injected[0] = true;
                        final Object[] entryLocals = (access & Opcodes.ACC_STATIC) != 0
                                ? new Object[0]
                                : new Object[] { reader.getClassName() };
                        return new MethodVisitor(COMPAT_API, mv) {
                            @Override
                            public void visitCode() {
                                super.visitCode();
                                emitInlineHook(this, entryLocals, needsFrames);
                            }
                        };
                    }
                    return mv;
                }
            };
            // EXPAND_FRAMES: frames arrive fully expanded, so the two we add cannot corrupt
            // the delta chain of the frames that follow them.
            reader.accept(injector, ClassReader.EXPAND_FRAMES);

            if (!injected[0]) {
                Agent.log("[Transformer] Display.update() (()V) not present — nothing injected");
                return null;
            }

            byte[] result = writer.toByteArray();
            hooked = true;
            Agent.log("[Transformer] Display.update() bytecode injected — RenderBridge.onFrame() will be called every frame");
            return result;
        } catch (Throwable e) {
            // catch(Throwable) is deliberate. A failure here (malformed class file, an
            // unexpected method shape, an Error from the verifier) must never be swallowed:
            // a silent null return with no diagnostics is exactly the failure mode that
            // cost days of guessing.
            //
            // The full stack is logged, not just the cause chain. ASM throws bare
            // IllegalArgumentException() with a null message from several places, and in
            // that case the cause chain prints nothing useful at all (observed in the
            // field: "Failed: IllegalArgumentException: null" with no way to locate it).
            Agent.log("[Transformer] Failed: " + e.getClass().getName() + ": " + e.getMessage());
            for (StackTraceElement el : e.getStackTrace()) {
                Agent.log("[Transformer]   at " + el);
            }
            Throwable cause = e.getCause();
            int depth = 0;
            while (cause != null && depth < 5) {
                Agent.log("[Transformer]   Caused by: " + cause.getClass().getName() + ": " + cause.getMessage());
                for (StackTraceElement el : cause.getStackTrace()) {
                    Agent.log("[Transformer]       at " + el);
                }
                cause = cause.getCause();
                depth++;
            }
        }
        return null;
    }

    /**
     * Emit, at the current position in the method being written: a best-effort reflective
     * call to RenderBridge.onFrame(), wrapped so that no failure can escape into update().
     *
     * Reflection instead of a direct INVOKESTATIC is deliberate. Display is defined by the
     * game's LaunchClassLoader, while RenderBridge lives in agent.jar; letting the JVM
     * resolve that reference from Display would put a ClassLoader it cannot see on the
     * resolution path, and a NoClassDefFoundError there would propagate straight into the
     * frame loop. Going through the render thread's context CL keeps the failure inside the
     * try block, where it is swallowed.
     *
     * @param entryLocals locals visible at method entry. The handler and the continuation both
     *                    have to restore exactly this shape, which is why the frames can be
     *                    written out instead of computed (see transformDisplay).
     */
    private static void emitInlineHook(MethodVisitor mv, Object[] entryLocals, boolean needsFrames) {
        Label start = new Label();
        Label end = new Label();
        Label handler = new Label();
        Label after = new Label();
        mv.visitTryCatchBlock(start, end, handler, "java/lang/Throwable");
        mv.visitLabel(start);
        mv.visitLdcInsn("io.doppel.agent.RenderBridge");
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Thread", "currentThread", "()Ljava/lang/Thread;", false);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Thread", "getContextClassLoader", "()Ljava/lang/ClassLoader;", false);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Class", "forName", "(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;", false);
        mv.visitLdcInsn("onFrame");
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Class");
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Class", "getMethod", "(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;", false);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object");
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/reflect/Method", "invoke", "(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;", false);
        mv.visitInsn(Opcodes.POP);
        mv.visitLabel(end);
        mv.visitJumpInsn(Opcodes.GOTO, after);
        mv.visitLabel(handler);
        if (needsFrames) {
            // Constant pool growth is allowed by retransformClasses, new frames are not
            // inherited — the handler is entered with the thrown object on the stack and the
            // same locals the method started with.
            mv.visitFrame(Opcodes.F_NEW, entryLocals.length, entryLocals, 1, new Object[] { "java/lang/Throwable" });
        }
        mv.visitInsn(Opcodes.POP);
        mv.visitLabel(after);
        if (needsFrames) {
            // Where the original body resumes: entry locals, empty stack.
            mv.visitFrame(Opcodes.F_NEW, entryLocals.length, entryLocals, 0, new Object[0]);
        }
    }

    /**
     * Inject the NoCameraBob early return into EntityRenderer.setupViewBobbing(float).
     *
     * Same shape as transformDisplay and for the same reasons: frames are written by hand (computing
     * them would need to resolve game types this ClassLoader cannot see), the original body is left
     * untouched, and only a single guarded jump is added at method entry.
     *
     * Accepts both the deobfuscated name and the SRG name because either may be in effect depending on
     * whether FML's remapper has already run when this class is presented to us.
     */
    private byte[] transformEntityRenderer(byte[] classfileBuffer) {
        try {
            ClassReader reader = new ClassReader(classfileBuffer);
            final boolean needsFrames = (reader.readShort(6) & 0xffff) >= 50;
            final boolean[] injected = new boolean[] { false };
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
            ClassVisitor injector = new ClassVisitor(COMPAT_API, writer) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                    MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                    if (mv == null) return null;
                    boolean isBob = ("setupViewBobbing".equals(name) || "func_78475_f".equals(name))
                            && "(F)V".equals(descriptor);
                    if (!injected[0] && isBob) {
                        injected[0] = true;
                        final Object[] entryLocals = (access & Opcodes.ACC_STATIC) != 0
                                ? new Object[] { Opcodes.FLOAT }
                                : new Object[] { reader.getClassName(), Opcodes.FLOAT };
                        return new MethodVisitor(COMPAT_API, mv) {
                            @Override
                            public void visitCode() {
                                super.visitCode();
                                emitSkipHook(this, entryLocals, needsFrames);
                            }
                        };
                    }
                    return mv;
                }
            };
            reader.accept(injector, 0);
            if (!injected[0]) {
                Agent.log("[Transformer] EntityRenderer: setupViewBobbing (F)V not found — bob hook NOT installed");
                return null;
            }
            byte[] out = writer.toByteArray();
            bobHooked = true;
            Agent.log("[Transformer] EntityRenderer.setupViewBobbing hooked — NoCameraBob can skip the camera bob");
            return out;
        } catch (Throwable t) {
            // Failing here is safe: returning null leaves the class exactly as it was, so the worst case
            // is a module that does nothing, never a client that does not start.
            Agent.log("[Transformer] EntityRenderer transform failed (hook NOT installed): " + t);
            return null;
        }
    }

    /**
     * Emit, at method entry: `try { if (RenderBridge.skipViewBobbing()) return; } catch (Throwable) {}`.
     *
     * The reflective lookup goes through the render thread's context ClassLoader for the same reason
     * emitInlineHook does — a failed resolution then lands in the catch instead of propagating out of a
     * render method.
     */
    private static void emitSkipHook(MethodVisitor mv, Object[] entryLocals, boolean needsFrames) {
        Label start = new Label();
        Label end = new Label();
        Label handler = new Label();
        Label after = new Label();
        mv.visitTryCatchBlock(start, end, handler, "java/lang/Throwable");
        mv.visitLabel(start);
        mv.visitLdcInsn("io.doppel.agent.RenderBridge");
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Thread", "currentThread", "()Ljava/lang/Thread;", false);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Thread", "getContextClassLoader", "()Ljava/lang/ClassLoader;", false);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Class", "forName", "(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;", false);
        mv.visitLdcInsn("skipViewBobbing");
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Class");
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Class", "getMethod", "(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;", false);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object");
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/reflect/Method", "invoke", "(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;", false);
        mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Boolean");
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Boolean", "booleanValue", "()Z", false);
        mv.visitJumpInsn(Opcodes.IFEQ, after);
        mv.visitInsn(Opcodes.RETURN);
        // The protected range ends exactly where the handler starts. Deliberately no trailing GOTO: the
        // instruction after a RETURN is an unreachable basic block, and the verifier demands a stack map
        // frame at it ("Expecting a stack map frame ... at goto") — the first VerifyError this hook hit.
        mv.visitLabel(end);
        mv.visitLabel(handler);
        if (needsFrames) {
            mv.visitFrame(Opcodes.F_NEW, entryLocals.length, entryLocals, 1, new Object[] { "java/lang/Throwable" });
        }
        mv.visitInsn(Opcodes.POP);
        mv.visitLabel(after);
        if (needsFrames) {
            mv.visitFrame(Opcodes.F_NEW, entryLocals.length, entryLocals, 0, new Object[0]);
        }
    }

    /** Locate EntityRenderer through the game ClassLoader, deobfuscated name first. */
    private static Class<?> findEntityRendererClass() {
        ClassLoader cl = null;
        try {
            Class<?> display = findDisplayClass();
            if (display != null) cl = display.getClassLoader();
        } catch (Throwable ignored) {}
        if (cl == null) return null;
        try {
            return Class.forName("net.minecraft.client.renderer.EntityRenderer", false, cl);
        } catch (Throwable t) {
            try {
                return Class.forName("bfk", false, cl);
            } catch (Throwable t2) {
                return null;
            }
        }
    }

    // ═══════════════════════════════════════════
    //  Instrumentation acquisition — Agent installs, Transformer only rewrites bytes
    // ═══════════════════════════════════════════

    // ═══════════════════════════════════════════
    //  Game ClassLoader resolution — bridge the JPLIS → game CL gap
    // ═══════════════════════════════════════════

    /**
     * Find org.lwjgl.opengl.Display class using the game ClassLoader.
     *
     * When agentmain() is called by the JPLIS agent, the Agent and Transformer
     * classes are loaded by the system CL (because JPLIS calls
     * appendToSystemClassLoaderSearch before loading the agent class).
     * The system CL cannot see LWJGL classes (they're on the game CL).
     *
     * This method tries multiple strategies to find the game CL and load
     * the Display class through it. The result is cached for subsequent calls.
     *
     * @return the Display class, loaded by the game CL
     * @throws ClassNotFoundException if Display cannot be found in any CL
     */
    private static Class<?> findDisplayClass() throws ClassNotFoundException {
        // Try cached game CL first (fast path)
        if (cachedGameCL != null) {
            try {
                return Class.forName("org.lwjgl.opengl.Display", true, cachedGameCL);
            } catch (ClassNotFoundException e) {
                Agent.log("[Transformer] Cached game CL failed to find Display, re-resolving...");
                cachedGameCL = null;
            }
        }

        ClassLoader contextCL = Thread.currentThread().getContextClassLoader();

        // Strategy 1 (PRIORITY): Forge LaunchClassLoader — the ONLY ClassLoader that
        // loads the Display class the game ACTUALLY uses at runtime.
        //
        // CRITICAL BUG FIX: the old code tried "caller ClassLoader" first. In the
        // jni-attach path the caller is the JPLIS AppClassLoader, and because the
        // PCL2 launcher puts lwjgl.jar on the SYSTEM classpath, Class.forName() from
        // AppClassLoader succeeds — but it returns a "zombie" Display class loaded by
        // AppClassLoader, NOT the one the game renders with (LaunchClassLoader).
        // Hooking the zombie class succeeds silently yet never renders anything.
        // (Confirmed in real logs: "Display found via caller CL: sun.misc.Launcher$AppClassLoader")
        //
        // LaunchClassLoader is found via the static field Launch.classLoader.
        try {
            ClassLoader searchCL = contextCL != null ? contextCL : ClassLoader.getSystemClassLoader();
            Class<?> launchClass = Class.forName("net.minecraft.launchwrapper.Launch", true, searchCL);
            java.lang.reflect.Field clField = launchClass.getField("classLoader");
            Object launchCL = clField.get(null);
            if (launchCL instanceof ClassLoader) {
                Class<?> cls = Class.forName("org.lwjgl.opengl.Display", true, (ClassLoader) launchCL);
                cachedGameCL = (ClassLoader) launchCL;
                Agent.log("[Transformer] Display found via Forge LaunchClassLoader");
                return cls;
            }
        } catch (ClassNotFoundException e) {
            Agent.log("[Transformer] LaunchClassLoader strategy: Display not found");
        } catch (Exception e) {
            Agent.log("[Transformer] LaunchClassLoader strategy failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        // Strategy 2: Thread context ClassLoader (render thread context CL)
        if (contextCL != null) {
            try {
                Class<?> cls = Class.forName("org.lwjgl.opengl.Display", true, contextCL);
                cachedGameCL = contextCL;
                Agent.log("[Transformer] Display found via context CL: " + contextCL.getClass().getName());
                return cls;
            } catch (ClassNotFoundException e) {
                Agent.log("[Transformer] Context CL (" + contextCL.getClass().getName() + ") cannot find Display");
            }
        }

        // Strategy 3: Walk system CL parent chain looking for a LaunchClassLoader
        try {
            ClassLoader cl = ClassLoader.getSystemClassLoader();
            while (cl != null) {
                if (cl.getClass().getName().contains("LaunchClassLoader")) {
                    try {
                        Class<?> cls = Class.forName("org.lwjgl.opengl.Display", true, cl);
                        cachedGameCL = cl;
                        Agent.log("[Transformer] Display found via LaunchClassLoader in system CL chain");
                        return cls;
                    } catch (ClassNotFoundException e2) {
                        // This LaunchClassLoader doesn't have Display
                    }
                }
                cl = cl.getParent();
            }
        } catch (Exception e) {
            Agent.log("[Transformer] System CL chain walk failed: " + e.getMessage());
        }

        // Strategy 4 (LAST RESORT): Caller's ClassLoader — ONLY accept if the found
        // Display is loaded by a LaunchClassLoader. A Display loaded by AppClassLoader
        // is a zombie class (lwjgl on system classpath) that the game never calls.
        try {
            Class<?> cls = Class.forName("org.lwjgl.opengl.Display");
            ClassLoader cl = cls.getClassLoader();
            if (cl != null && cl.getClass().getName().contains("LaunchClassLoader")) {
                cachedGameCL = cl;
                Agent.log("[Transformer] Display found via caller CL: " + cl.getClass().getName());
                return cls;
            }
            Agent.log("[Transformer] Caller CL found Display but it is a zombie class (CL=" +
                (cl != null ? cl.getClass().getName() : "bootstrap") + ") — rejected");
        } catch (ClassNotFoundException e) {
            Agent.log("[Transformer] Caller CL cannot find Display");
        }

        throw new ClassNotFoundException(
            "org.lwjgl.opengl.Display — not found in any ClassLoader. " +
            "Caller CL: " + Transformer.class.getClassLoader() + ", " +
            "Context CL: " + contextCL + ", " +
            "System CL: " + ClassLoader.getSystemClassLoader());
    }

    private static void appendAgentToBootstrapCL(Instrumentation inst) {
        try {
            String agentJarPath = findAgentJar();
            if (agentJarPath != null) {
                inst.appendToBootstrapClassLoaderSearch(new java.util.jar.JarFile(agentJarPath));
                Agent.log("[Transformer] Agent jar appended to bootstrap classloader: " + agentJarPath);
            }
        } catch (Exception e) {
            Agent.log("[Transformer] Failed to append to bootstrap CL: " + e.getMessage());
        }
    }

    private static String findAgentJar() {
        String tempDir = System.getProperty("java.io.tmpdir");
        String[] paths = {
            tempDir + "/doppel-agent.jar",
            tempDir + "\\doppel-agent.jar"
        };
        for (String path : paths) {
            if (new File(path).exists()) {
                return path;
            }
        }
        return null;
    }

    private static volatile boolean toolsJarLoaded = false;

    /**
     * On JDK 8, com.sun.tools.attach lives in tools.jar which is not on
     * the default classpath. This method adds it dynamically so we can
     * use VirtualMachine.attach() to obtain an Instrumentation instance.
     */
    static void ensureToolsJar() {
        if (toolsJarLoaded) return;
        try {
            Class.forName("com.sun.tools.attach.VirtualMachine");
            toolsJarLoaded = true;
            return;
        } catch (ClassNotFoundException ignored) {}

        String javaHome = System.getProperty("java.home");
        File toolsJar = new File(javaHome, "../lib/tools.jar");
        if (!toolsJar.exists()) {
            toolsJar = new File(javaHome, "lib/tools.jar");
        }
        if (toolsJar.exists()) {
            try {
                URL jarUrl = toolsJar.toURI().toURL();
                Method addUrl = URLClassLoader.class.getDeclaredMethod("addURL", URL.class);
                addUrl.setAccessible(true);
                addUrl.invoke(ClassLoader.getSystemClassLoader(), jarUrl);
                toolsJarLoaded = true;
                Agent.log("[Transformer] tools.jar added to classpath: " + toolsJar.getAbsolutePath());
            } catch (Exception e) {
                Agent.log("[Transformer] Failed to add tools.jar: " + e.getMessage());
            }
        } else {
            Agent.log("[Transformer] tools.jar not found (searched " + javaHome + ")");
        }
    }

    static String getProcessPid() {
        try {
            Class<?> rtMxBeanClass = Class.forName("java.lang.management.RuntimeMXBean");
            Method getNameMethod = rtMxBeanClass.getMethod("getName");
            Object rtMxBean = java.lang.management.ManagementFactory.getRuntimeMXBean();
            String name = (String) getNameMethod.invoke(rtMxBean);
            int at = name.indexOf('@');
            return at > 0 ? name.substring(0, at) : null;
        } catch (Exception e) {
            return null;
        }
    }
}
