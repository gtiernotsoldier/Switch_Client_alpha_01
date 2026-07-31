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
                Class<?> displayClass = Class.forName("org.lwjgl.opengl.Display");
                if (inst.isModifiableClass(displayClass)) {
                    appendAgentToBootstrapCL(inst);
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
            appendAgentToBootstrapCL(inst);
            inst.addTransformer(new Transformer(), true);
            Class<?> displayClass = Class.forName("org.lwjgl.opengl.Display");
            inst.retransformClasses(displayClass);

            // Same false-positive fix as install() — check hooked flag
            if (hooked) {
                Agent.log("[Transformer] Display.update() hooked via self-attach retransform");
                installed = true;
            } else {
                Agent.log("[Transformer] handleRetransform: retransformClasses completed but transform() returned null");
            }
        } catch (Exception e) {
            Agent.log("[Transformer] handleRetransform failed: " + e.getMessage());
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

            // CRITICAL FIX: Javassist's ClassPool does NOT know about classes added via
            // appendToBootstrapClassLoaderSearch(). When insertBefore() compiles the source
            // code "io.switchlite.agent.RenderBridge.onFrame()", Javassist needs to resolve
            // the RenderBridge class. Without this, it throws:
            //   CannotCompileException: [source error] no such class: io$switchlite.agent.RenderBridge
            //
            // Strategy 1: Add LoaderClassPath for the game's classloader.
            // The Transformer class is loaded by the game's LaunchClassLoader (same CL that
            // loads RenderBridge). Adding this CL to the pool lets Javassist find all classes
            // in the agent.jar, including RenderBridge, through the classloader directly.
            // This is more reliable than appendClassPath(String) because it avoids Windows
            // path format issues (mixed slashes, 8.3 short names) that can prevent Javassist
            // from reading the jar.
            try {
                ClassLoader gameCL = Transformer.class.getClassLoader();
                if (gameCL != null) {
                    pool.appendClassPath(new javassist.LoaderClassPath(gameCL));
                    Agent.log("[Transformer] Added game ClassLoader to ClassPool: " + gameCL.getClass().getName());
                }
            } catch (Exception e) {
                Agent.log("[Transformer] Failed to add game ClassLoader to ClassPool: " + e.getMessage());
            }

            // Strategy 2: Also add the agent.jar directly to the pool as a fallback.
            // Some Javassist versions or configurations may not search LoaderClassPath
            // for source compilation. Adding the jar directly ensures the pool can find
            // the class by reading the jar entries.
            // Use getCanonicalPath() to normalize Windows path (mixed slashes, 8.3 names).
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
                Agent.log("[Transformer] findAgentJar() returned null — cannot add agent.jar to ClassPool");
            }

            // Strategy 3: Verify that RenderBridge is actually findable in the pool.
            // This diagnostic helps us understand if the ClassPool fix is working.
            try {
                pool.get("io.switchlite.agent.RenderBridge");
                Agent.log("[Transformer] RenderBridge found in ClassPool — compilation should succeed");
            } catch (javassist.NotFoundException e) {
                Agent.log("[Transformer] WARNING: RenderBridge NOT found in ClassPool — compilation will likely fail");
                Agent.log("[Transformer] This means neither LoaderClassPath nor jar classpath made RenderBridge available");
            }

            CtClass ctClass = pool.makeClass(new ByteArrayInputStream(classfileBuffer));

            CtMethod updateMethod = ctClass.getDeclaredMethod("update");

            // Insert RenderBridge.onFrame() at the very beginning of Display.update()
            // Before: Display processes events + swaps buffers
            // After:  RenderBridge.onFrame() -> Display processes events + swaps buffers
            updateMethod.insertBefore(
                "io.switchlite.agent.RenderBridge.onFrame();"
            );

            byte[] result = ctClass.toBytecode();
            ctClass.defrost();
            hooked = true;

            Agent.log("[Transformer] Display.update() bytecode injected — RenderBridge.onFrame() will be called every frame");
            return result;
        } catch (Exception e) {
            // NOTE: We use a single catch(Exception) instead of separate catches for
            // CannotCompileException and NotFoundException because of classloader isolation.
            // When appendToBootstrapClassLoaderSearch() is called, the Javassist classes
            // loaded by the bootstrap CL are different from those loaded by the game CL.
            // The catch(CannotCompileException) clause uses the game CL's version, but the
            // actual exception thrown is from the bootstrap CL's version — so the catch
            // doesn't match and falls through to catch(Exception).
            String errorType = e.getClass().getSimpleName();
            String errorMsg = e.getMessage();
            if ("CannotCompileException".equals(errorType)) {
                Agent.log("[Transformer] Compile error: " + errorMsg);
            } else if ("NotFoundException".equals(errorType)) {
                Agent.log("[Transformer] Display.update() not found: " + errorMsg);
            } else {
                Agent.log("[Transformer] Failed: " + errorType + ": " + errorMsg);
            }
        }
        return null;
    }

    // ═══════════════════════════════════════════
    //  Instrumentation acquisition — this is Javassist's job, not Agent's
    // ═══════════════════════════════════════════

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
