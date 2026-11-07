package io.doppel.ipc

import io.doppel.contract.logging.CoreLogger
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Owns the `doppel-core` process and the socket that goes with it.
 *
 * ## Why this has to exist before anything is switched over
 *
 * `Strategies.aim()` returns an IPC-backed strategy the moment [CoreLink.stub]
 * is set, and an inert one otherwise. Nothing else in the client assigns that
 * field, so without this class the adapters would talk to a session that is
 * never established — every module would look enabled and do nothing, with no
 * log line saying why. That failure mode is indistinguishable from a bug in the
 * algorithm, which is the worst way to ship a degradation path.
 *
 * ## What it does not do
 *
 * No retry storm, no config writing, no UI. A crash is logged once and the stub
 * is cleared; the modules go quiet until the next start. Restart policy belongs
 * to whoever calls [start], because only that layer knows whether the user is in
 * a game, in a menu, or shutting down.
 */
object CoreSupervisor {

    private const val HANDSHAKE_TIMEOUT_MS = 3_000L
    private const val BANNER_TIMEOUT_MS = 5_000L

    private var process: Process? = null
    private var stub: CoreStub? = null
    private var watchdog: Thread? = null

    /** True when a core session is attached and its process is alive. */
    @Volatile
    var running: Boolean = false
        private set

    /**
     * Locates the binary, starts it, connects and publishes the stub.
     *
     * Returns false — without throwing — when no binary is present. That is a
     * normal state on a machine that only has the jar, and it must not take the
     * client down with it. Callers should surface it once (`CoreLogger.warn`),
     * not per tick.
     */
    @Synchronized
    fun start(): Boolean {
        if (running) return true

        val exe = locate() ?: run {
            CoreLogger.warn("[CoreSupervisor] doppel-core not found; algorithm modules will stay inactive")
            return false
        }

        val portFile = File.createTempFile("doppel-core-", ".port").apply { deleteOnExit() }
        val seed = System.nanoTime() and Long.MAX_VALUE

        val p = try {
            ProcessBuilder(
                exe.absolutePath,
                "--listen", "tcp:127.0.0.1:0",
                "--seed", seed.toString(),
                "--port-file", portFile.absolutePath
            ).redirectError(ProcessBuilder.Redirect.INHERIT).start()
        } catch (e: Exception) {
            CoreLogger.warn("[CoreSupervisor] could not start ${exe.absolutePath}: ${e.message}")
            return false
        }

        val banner = readBanner(p) ?: run {
            CoreLogger.warn("[CoreSupervisor] no LISTEN banner from ${exe.name}; core unusable")
            p.destroyForcibly()
            return false
        }

        // The banner carries a bare `host:port` ("LISTEN 127.0.0.1:35099"), because the
        // core prints `listener.local()` — it drops the scheme. Endpoint.parse wants one.
        val advertised = banner.removePrefix("LISTEN ").trim()
        val endpoint = try {
            Endpoint.parse(if (advertised.startsWith("unix:")) advertised else "tcp:$advertised")
        } catch (e: Exception) {
            CoreLogger.warn("[CoreSupervisor] bad endpoint \"$banner\": ${e.message}")
            p.destroyForcibly()
            return false
        }

        return try {
            val s = CoreStub(endpoint, null, deadlineNanos = 20_000_000L)
            s.connect()
            process = p
            stub = s
            CoreLink.stub = s
            running = true
            CoreLogger.info("[CoreSupervisor] core ${p.pid()} on $endpoint, seed=$seed")
            startWatchdog(p, s)
            true
        } catch (e: Exception) {
            CoreLogger.warn("[CoreSupervisor] handshake failed: ${e.message}")
            p.destroyForcibly()
            false
        }
    }

    /** Detaches the stub first so no tick can start a round trip on a closing socket. */
    @Synchronized
    fun stop() {
        if (!running && process == null) return
        CoreLink.stub = null
        running = false
        try {
            stub?.close()
        } catch (_: Exception) {
        }
        stub = null
        val p = process
        process = null
        watchdog?.interrupt()
        watchdog = null
        if (p != null) {
            p.destroy()
            if (!p.waitFor(1, TimeUnit.SECONDS)) p.destroyForcibly()
        }
        CoreLogger.info("[CoreSupervisor] stopped")
    }

    private fun startWatchdog(p: Process, s: CoreStub) {
        val t = Thread({
            try {
                val code = p.waitFor()
                if (CoreLink.stub === s) {
                    CoreLink.stub = null
                    running = false
                    CoreLogger.warn("[CoreSupervisor] core exited (code=$code); modules inactive")
                }
            } catch (_: InterruptedException) {
                // stop() did this on purpose.
            }
        }, "doppel-core-watchdog")
        t.isDaemon = true
        watchdog = t
        t.start()
    }

    /**
     * The first stdout line, or null on timeout. `--listen :0` means the port is
     * only known after bind, so the banner is the only way to learn it.
     */
    private fun readBanner(p: Process): String? {
        val reader = BufferedReader(InputStreamReader(p.inputStream))
        val line = java.util.concurrent.CompletableFuture.supplyAsync { reader.readLine() }
        return try {
            line.get(BANNER_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Search order: explicit override, next to the jar, the repo's cargo output,
     * then PATH. The cargo entries are what makes a developer run work without
     * setting a property; a released client ships the binary beside the jar.
     */
    private fun locate(): File? {
        val override = System.getProperty("doppel.core.path")
        if (!override.isNullOrBlank()) {
            val f = File(override)
            if (f.canExecute()) return f
        }

        val home = File(System.getProperty("user.dir") ?: ".")
        val jarDir = runCatching {
            File(CoreSupervisor::class.java.protectionDomain.codeSource.location.toURI()).parentFile
        }.getOrNull()

        val candidates = buildList {
            if (jarDir != null) {
                add(File(jarDir, exeName()))
                add(File(jarDir, "core/${exeName()}"))
            }
            add(File(home, "doppel-core/${exeName()}"))
            add(File(home, "Doppel/core-rs/target/release/${exeName()}"))
            add(File(home, "Doppel/core-rs/target/debug/${exeName()}"))
            add(File(home, "core-rs/target/release/${exeName()}"))
            add(File(home, "core-rs/target/debug/${exeName()}"))
        }
        candidates.firstOrNull { it.isFile && it.canExecute() }?.let { return it }

        return System.getenv("PATH")
            ?.split(File.pathSeparator)
            ?.map { File(it, exeName()) }
            ?.firstOrNull { it.isFile && it.canExecute() }
    }

    private fun exeName(): String =
        if (System.getProperty("os.name").orEmpty().startsWith("Windows")) "doppel-core.exe" else "doppel-core"
}
