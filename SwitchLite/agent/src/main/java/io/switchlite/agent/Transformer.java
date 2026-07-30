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
 * - Injects RenderHook.onFrame() call into Display.update()
 * - RenderHook then delegates to ForgeBootstrap.render() on the MC render thread
 *
 * Stealth advantage: Bytecode injection into the method body is invisible to
 * task-queue scanning and thread-based detection. Anti-cheat that checks
 * addScheduledTask, reflection, or thread lists won't find it.
 */
public class Transformer implements ClassFileTransformer {

    private static boolean hooked = false;
    private static volatile boolean installed = false;

    /**
     * Whether the Display.update() hook was successfully installed.
     * Agent.java checks this to decide whether to use the addScheduledTask fallback.
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
                    Agent.log("[Transformer] Display.update() hooked via Instrumentation.retransformClasses");
                    installed = true;
                    return true;
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

        Agent.log("[Transformer] Could not install Display.update() hook — will use addScheduledTask fallback");
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
            Agent.log("[Transformer] Display.update() hooked via self-attach retransform");
            installed = true;
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
            CtClass ctClass = pool.makeClass(new ByteArrayInputStream(classfileBuffer));

            CtMethod updateMethod = ctClass.getDeclaredMethod("update");

            // Insert RenderHook.onFrame() at the very beginning of Display.update()
            // Before: Display processes events + swaps buffers
            // After:  RenderHook.onFrame() -> Display processes events + swaps buffers
            updateMethod.insertBefore(
                "io.switchlite.agent.RenderHook.onFrame();"
            );

            byte[] result = ctClass.toBytecode();
            ctClass.defrost();
            hooked = true;

            Agent.log("[Transformer] Display.update() bytecode injected — RenderHook.onFrame() will be called every frame");
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
