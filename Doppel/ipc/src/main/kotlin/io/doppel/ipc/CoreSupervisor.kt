package io.doppel.ipc

import io.doppel.contract.logging.CoreLogger
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Connects the Kotlin side to the algorithm process.
 *
 * ## Ownership (PROTECTION.md §9.4)
 *
 * The `doppel-core` process belongs to the **injector**, not to the game JVM.
 * The injector starts it before injection and publishes its address as
 * `doppel.core.endpoint` (via `doppel-config.properties`). In that production
 * path this class is a *pure connector*: it opens the socket, completes the
 * handshake and publishes [CoreLink.stub]. It never spawns, locates, supervises
 * or kills a process — that is why `attach` has no watchdog.
 *
 * `spawnDev()` below is a **development/test-only fallback**, reached only when
 * no endpoint was published and `doppel.core.path` names a binary explicitly.
 * It keeps the parity suites runnable on a machine with no injector. A release
 * build never sets that property, so the game JVM never launches the algorithm
 * process itself.
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
 * No retry storm, no config writing, no UI, no process lifecycle. A failed
 * connect is logged once and the modules stay offline. Restarting the core is
 * the injector's job, because only that layer owns it.
 */
object CoreSupervisor {

    /** Endpoint published by the injector. Production path. */
    const val ENDPOINT_PROPERTY = "doppel.core.endpoint"

    /** Dev/test only: spawn a local binary. The injector never sets this. */
    const val DEV_BINARY_PROPERTY = "doppel.core.path"

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
     * Connect to the algorithm process. Two paths:
     *
     * 1. `doppel.core.endpoint` is set (production, injector owns the core):
     *    connect only, no process to supervise.
     * 2. otherwise `doppel.core.path` (dev/test fallback): spawn locally.
     *
     * Returns false — without throwing — when neither is available. That is a
     * normal state on a machine with no injector, and it must not take the
     * client down with it. Callers surface it once (`CoreLogger.warn`), not per
     * tick.
     */
    @Synchronized
    fun start(): Boolean {
        if (running) return true

        val external = System.getProperty(ENDPOINT_PROPERTY)
        if (!external.isNullOrBlank()) return attach(external)

        return spawnDev()
    }

    /**
     * Production path: the injector already started the core. Parse the
     * published endpoint, connect, publish the stub. Nothing here owns a
     * process, so there is deliberately no watchdog — a dead core is the
     * injector's problem to restart, and [CoreStub] reconnects on the next tick.
     */
    private fun attach(spec: String): Boolean {
        val endpoint = try {
            Endpoint.parse(spec)
        } catch (e: Exception) {
            CoreLogger.warn("[CoreSupervisor] bad endpoint \"$spec\": ${e.message}")
            return false
        }
        return try {
            val s = CoreStub(endpoint, null, deadlineNanos = 20_000_000L)
            s.connect()
            stub = s
            CoreLink.stub = s
            running = true
            externallyOwned = true
            CoreLogger.info("[CoreSupervisor] attached to injector-owned core at $spec")
            true
        } catch (e: Exception) {
            CoreLogger.warn("[CoreSupervisor] attach to $spec failed: ${e.message}")
            false
        }
    }

    /** True when the core is an external process owned by the injector. */
    @Volatile
    var externallyOwned: Boolean = false
        private set

    /**
     * Dev/test fallback: locate the binary, start it, connect and publish the
     * stub. This is the only path that spawns a process, and it is reachable
     * only when the injector published no endpoint.
     */
    private fun spawnDev(): Boolean {
        val exe = locate() ?: run {
            CoreLogger.warn("[CoreSupervisor] no core endpoint and no local binary; algorithm modules stay inactive")
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
            externallyOwned = false
            CoreLogger.info("[CoreSupervisor] core started on $endpoint, seed=$seed")
            startWatchdog(p, s)
            true
        } catch (e: Exception) {
            CoreLogger.warn("[CoreSupervisor] handshake failed: ${e.message}")
            p.destroyForcibly()
            false
        }
    }

    /**
     * Detaches the stub first so no tick can start a round trip on a closing
     * socket. When the core is injector-owned, `process` is null and nothing is
     * killed here — only the connection is dropped. The injector decides when
     * the algorithm process goes away.
     */
    @Synchronized
    fun stop() {
        if (!running && process == null) return
        CoreLink.stub = null
        running = false
        externallyOwned = false
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
     * Dev/test fallback only: **explicit** `doppel.core.path`, nothing implicit.
     *
     * The old version also scanned the jar directory, cargo target dirs and PATH.
     * That had to go: it let the game JVM silently start an algorithm process on
     * its own, which is exactly the ownership inversion §9.4 rules out. A
     * release build sets neither property, so `start()` returns false and the
     * algorithm modules stay offline until the injector publishes an endpoint.
     */
    private fun locate(): File? {
        val override = System.getProperty(DEV_BINARY_PROPERTY)
        if (!override.isNullOrBlank()) {
            val f = File(override)
            if (f.canExecute()) return f
        }
        return null
    }
}
