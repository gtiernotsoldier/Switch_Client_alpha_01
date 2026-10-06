package io.doppel.agent;

import java.io.File;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.security.ProtectionDomain;
import java.util.HashSet;
import java.util.Set;

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
            final Set<String> existingMethods = new HashSet<String>();
            reader.accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                    existingMethods.add(name);
                    return null;
                }
            }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

            // Short, non-branded name (a synthetic method is visible in a heap dump, and the
            // plan's de-characterisation rule applies to bytecode too). Collisions are checked.
            String hookName = "h";
            while (existingMethods.contains(hookName)) hookName = hookName + "h";
            final String hook = hookName;
            final String owner = reader.getClassName();
            // Frames are mandatory from class file version 50 (Java 6) onwards; LWJGL 2.x may
            // be older, in which case writing a StackMapTable would itself be invalid.
            final boolean needsFrames = (reader.readShort(6) & 0xffff) >= 50;

            final boolean[] injected = new boolean[] { false };
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
            ClassVisitor injector = new ClassVisitor(Opcodes.ASM9, writer) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                    MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                    if (mv == null) return null;
                    if (!injected[0] && "update".equals(name) && "()V".equals(descriptor)) {
                        injected[0] = true;
                        return new MethodVisitor(Opcodes.ASM9, mv) {
                            @Override
                            public void visitCode() {
                                super.visitCode();
                                // Display.update() must run the hook before anything else.
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, owner, hook, "()V", false);
                            }
                        };
                    }
                    return mv;
                }

                @Override
                public void visitEnd() {
                    if (injected[0]) emitHookMethod(this, hook, needsFrames);
                    super.visitEnd();
                }
            };
            reader.accept(injector, 0);

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
            // cost days of guessing. Print the type, the message and the cause chain.
            Agent.log("[Transformer] Failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            Throwable cause = e;
            int depth = 0;
            while (cause != null && depth < 5) {
                Agent.log("[Transformer]   Caused by: " + cause.getClass().getName() + ": " + cause.getMessage());
                cause = cause.getCause();
                depth++;
            }
        }
        return null;
    }

    /**
     * Emit the hook body as a new private static synthetic method on the class being
     * transformed: a best-effort reflective call to RenderBridge.onFrame() that swallows
     * every failure.
     *
     * Reflection instead of a direct INVOKESTATIC is deliberate. Display is defined by the
     * game's LaunchClassLoader, while RenderBridge lives in agent.jar; letting the JVM
     * resolve that reference from Display would put a ClassLoader it cannot see on the
     * resolution path, and a NoClassDefFoundError there would propagate straight into
     * Display.update(). Going through the render thread's context CL keeps the failure
     * inside the hook, where it is caught.
     *
     * Frames are written by hand because the writer runs with COMPUTE_MAXS only: computing
     * frames for a game class would require resolving game types (see transformDisplay).
     */
    private static void emitHookMethod(ClassVisitor cv, String hookName, boolean needsFrames) {
        MethodVisitor hm = cv.visitMethod(
            Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
            hookName, "()V", null, null);
        hm.visitCode();
        Label start = new Label();
        Label end = new Label();
        Label handler = new Label();
        Label after = new Label();
        hm.visitTryCatchBlock(start, end, handler, "java/lang/Throwable");
        hm.visitLabel(start);
        hm.visitLdcInsn("io.doppel.agent.RenderBridge");
        hm.visitInsn(Opcodes.ICONST_1);
        hm.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Thread", "currentThread", "()Ljava/lang/Thread;", false);
        hm.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Thread", "getContextClassLoader", "()Ljava/lang/ClassLoader;", false);
        hm.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Class", "forName", "(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;", false);
        hm.visitLdcInsn("onFrame");
        hm.visitInsn(Opcodes.ICONST_0);
        hm.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Class");
        hm.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Class", "getMethod", "(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;", false);
        hm.visitInsn(Opcodes.ACONST_NULL);
        hm.visitInsn(Opcodes.ICONST_0);
        hm.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object");
        hm.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/reflect/Method", "invoke", "(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;", false);
        hm.visitInsn(Opcodes.POP);
        hm.visitLabel(end);
        hm.visitJumpInsn(Opcodes.GOTO, after);
        hm.visitLabel(handler);
        // Handler entry state: no locals (static, no arguments), the thrown object on the stack.
        if (needsFrames) {
            hm.visitFrame(Opcodes.F_FULL, 0, new Object[0], 1, new Object[] { "java/lang/Throwable" });
        }
        hm.visitInsn(Opcodes.POP);
        hm.visitLabel(after);
        if (needsFrames) {
            hm.visitFrame(Opcodes.F_FULL, 0, new Object[0], 0, new Object[0]);
        }
        hm.visitInsn(Opcodes.RETURN);
        hm.visitMaxs(0, 0);
        hm.visitEnd();
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
