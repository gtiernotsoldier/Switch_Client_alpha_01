package io.switchlite.agent;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.security.ProtectionDomain;

import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtMethod;

/**
 * Javassist bytecode injection layer — hooks Display.update() for HUD rendering.
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
                    // CRITICAL FIX: do NOT append agent.jar to the bootstrap ClassLoader.
                    // Real log evidence (2026-08-05): appendAgentToBootstrapCL() caused
                    // javassist to be loaded by the bootstrap CL while Transformer was
                    // loaded by AppClassLoader (JPLIS agentmain). Two ClassLoaders each
                    // define javassist.ClassPath → LinkageError: loader constraint
                    // violation when Transformer calls ClassPool.appendClassPath(...).
                    //
                    // agent.jar is ALREADY on the system CL (JPLIS appends it to load
                    // this agent), so AppClassLoader can see javassist + RenderBridge.
                    // The injected bytecode loads RenderBridge via the render thread's
                    // context CL (LaunchClassLoader, which has agent.jar via payload.dll
                    // addURL) — no bootstrap CL involvement needed anywhere.
                    inst.addTransformer(new Transformer(), true);
                    inst.retransformClasses(displayClass);

                    // CRITICAL: retransformClasses() does NOT throw if transform() returns null.
                    // We must check the 'hooked' flag to know if the bytecode was actually modified.
                    // Previously, installed=true was set unconditionally — a false positive that
                    // made Agent think the rendering pipeline was active when it was actually dead.
                    if (hooked) {
                        Agent.log("[Transformer] Display.update() hooked via Instrumentation.retransformClasses");
                        installed = true;
                        return true;
                    } else {
                        Agent.log("[Transformer] FATAL: retransformClasses completed but transform() returned null — bytecode was NOT modified");
                        Agent.log("[Transformer] This usually means Javassist could not find RenderBridge in its ClassPool");
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
            // to bootstrap CL (causes javassist dual-classloader LinkageError).
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
        if (!"org/lwjgl/opengl/Display".equals(className)) {
            return null;
        }
        if (hooked) return null;

        try {
            ClassPool pool = ClassPool.getDefault();

            // NOTE: We previously tried to add RenderBridge to the ClassPool so that
            // insertBefore("io.switchlite.agent.RenderBridge.onFrame()") could compile.
            // That approach failed because:
            //   - appendToBootstrapClassLoaderSearch() puts the jar on the bootstrap CL,
            //     but Javassist's ClassPool.getDefault() does NOT search the bootstrap CL
            //   - LoaderClassPath(gameCL) may return null if Transformer is on bootstrap CL
            //   - findAgentJar() may not find the jar at the expected path
            //
            // Instead, we now use Class.forName() + reflection in the injected code, which
            // only requires java.lang.Class and java.lang.reflect.Method (always available).
            // No ClassPool modifications needed for compilation.
            //
            // We still add the game ClassLoader and agent.jar to the pool as a diagnostic —
            // if future insertBefore() calls need to reference other classes, they'll benefit.

            // Diagnostic: log which ClassLoader loaded Transformer
            ClassLoader transformerCL = Transformer.class.getClassLoader();
            Agent.log("[Transformer] Transformer classloader: " + (transformerCL != null ? transformerCL.getClass().getName() : "bootstrap (null)"));

            // Diagnostic: confirm javassist is visible and from which CL.
            // If this throws, the next catch(Throwable) will print the chain.
            Agent.log("[Transformer] javassist.ClassPool loaded by: " + javassist.ClassPool.class.getClassLoader());

            try {
                if (transformerCL != null) {
                    pool.appendClassPath(new javassist.LoaderClassPath(transformerCL));
                    Agent.log("[Transformer] Added Transformer CL to ClassPool");
                }
            } catch (Exception e) {
                Agent.log("[Transformer] Failed to add Transformer CL to ClassPool: " + e.getMessage());
            }

            String agentJarPath = findAgentJar();
            if (agentJarPath != null) {
                try {
                    String canonicalPath = new File(agentJarPath).getCanonicalPath();
                    pool.appendClassPath(canonicalPath);
                    Agent.log("[Transformer] Added agent.jar to ClassPool: " + canonicalPath);
                } catch (Exception e) {
                    Agent.log("[Transformer] Failed to add agent.jar to ClassPool: " + e.getMessage());
                }
            } else {
                Agent.log("[Transformer] findAgentJar() returned null — no jar path added to ClassPool");
            }

            // Diagnostic: check if RenderBridge is findable in the pool (informational only)
            try {
                pool.get("io.switchlite.agent.RenderBridge");
                Agent.log("[Transformer] RenderBridge found in ClassPool (informational — not needed for compilation)");
            } catch (javassist.NotFoundException e) {
                Agent.log("[Transformer] RenderBridge NOT found in ClassPool (OK — we use Class.forName() at runtime)");
            }

            CtClass ctClass = pool.makeClass(new ByteArrayInputStream(classfileBuffer));

            CtMethod updateMethod = ctClass.getDeclaredMethod("update");

            // Insert RenderBridge.onFrame() at the very beginning of Display.update()
            // Before: Display processes events + swaps buffers
            // After:  RenderBridge.onFrame() -> Display processes events + swaps buffers
            //
            // CRITICAL #1 — prevent Javassist constant folding:
            // Javassist 3.29.2's compiler performs compile-time constant folding on
            // `"io.switchlite.agent." + "RenderBridge"`, producing the literal
            // "io.switchlite.agent.RenderBridge". It then treats the string argument of
            // Class.forName(String) as a class reference and tries to resolve it at
            // COMPILE time via ClassPool → CannotCompileException:
            //   "no such class: io$switchlite.agent.RenderBridge"
            // (confirmed in real test logs — the simple concatenation does NOT prevent this).
            //
            // Fix: wrap the right operand in `new String("RenderBridge")`. A constructor
            // call is not a compile-time constant, so the expression can never be folded.
            // Class.forName() then receives a runtime string — no compile-time class
            // resolution, no CannotCompileException.
            //
            // CRITICAL #2 — load RenderBridge via context ClassLoader:
            // The previous `Class.forName(..., true, null)` used the bootstrap CL, which
            // only works if appendToBootstrapClassLoaderSearch succeeded. That chain is
            // fragile (depends on findAgentJar + %TEMP% jar + append timing).
            //
            // The MC render thread's context ClassLoader is the Forge LaunchClassLoader,
            // and payload.dll already addURL'd agent.jar into it (that's how Agent,
            // ForgeBootstrap and all 34 modules are loaded). So loading RenderBridge via
            // Thread.currentThread().getContextClassLoader() is guaranteed to work —
            // no bootstrap CL dependency at all.
            //
            // Performance: Class.forName() on an already-loaded class is ~1μs, getMethod()
            // is ~1μs (JVM caches reflection), invoke() fast path is ~0.1μs. Total ~2μs/frame
            // at 60fps = 120μs/s — negligible vs. 16ms render time per frame.
            updateMethod.insertBefore(
                "try { Class.forName(\"io.switchlite.agent.\" + new String(\"RenderBridge\"), true, " +
                "Thread.currentThread().getContextClassLoader()).getMethod(\"onFrame\").invoke(null); } catch (Throwable t) {}"
            );

            byte[] result = ctClass.toBytecode();
            ctClass.defrost();
            hooked = true;

            Agent.log("[Transformer] Display.update() bytecode injected — RenderBridge.onFrame() will be called every frame");
            return result;
        } catch (Throwable e) {
            // NOTE: catch(Throwable) — must catch Errors too. NoClassDefFoundError
            // (javassist missing from some classloader view) is an Error, NOT an
            // Exception, so a catch(Exception) silently drops it and transform()
            // returns null with zero diagnostics (confirmed in real logs).
            String errorType = e.getClass().getSimpleName();
            String errorMsg = e.getMessage();
            if ("CannotCompileException".equals(errorType)) {
                Agent.log("[Transformer] Compile error: " + errorMsg);
            } else if ("NotFoundException".equals(errorType)) {
                Agent.log("[Transformer] Display.update() not found: " + errorMsg);
            } else {
                Agent.log("[Transformer] Failed: " + errorType + ": " + errorMsg);
                // Print the root cause chain — helps find javassist linkage issues
                Throwable cause = e;
                int depth = 0;
                while (cause != null && depth < 5) {
                    Agent.log("[Transformer]   Caused by: " + cause.getClass().getName() + ": " + cause.getMessage());
                    cause = cause.getCause();
                    depth++;
                }
            }
        }
        return null;
    }

    // ═══════════════════════════════════════════
    //  Instrumentation acquisition — this is Javassist's job, not Agent's
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
            tempDir + "/switchlite-agent.jar",
            tempDir + "\\switchlite-agent.jar"
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
