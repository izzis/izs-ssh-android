package id.web.izs.sshclient.core.ssh

import id.web.izs.sshclient.core.config.ForwardedPort
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.Parameters
import net.schmizz.sshj.connection.channel.forwarded.RemotePortForwarder
import net.schmizz.sshj.connection.channel.forwarded.SocketForwardingConnectListener
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

/**
 * Local + Remote port forwarding at connect — desktop `addPortForward`
 * parity (tabby-ssh/src/session/ssh.ts:786-830).
 *
 * Semantics (desktop `ForwardedPort.toString`, forwards.ts:56-64):
 * - Local: listen on `host:port` on THIS device, forward into the server
 *   side `targetAddress:targetPort`.
 * - Remote: the SERVER listens on `host:port`, forward back to
 *   `targetAddress:targetPort` on this device.
 * - Dynamic (device-side SOCKS): desktop-only, like proxyCommand — a clear
 *   error, never silently skipped.
 *
 * Failure parity (ssh.ts:808-826): a Local bind failure aborts the connect
 * (throw); a server-side Remote rejection warns and the session survives.
 * Started forwards die with the transport ([StartedForwards.close]).
 *
 * Pure JVM (no Android APIs) — unit-testable, including against a local
 * MINA sshd like the multiplex tests.
 */
data class ForwardSpec(
    val type: String,
    /** Listen interface, blank-normalized to 127.0.0.1. */
    val host: String,
    val port: Int,
    val targetAddress: String,
    val targetPort: Int,
    val description: String,
)

/**
 * Validate + normalize raw [ForwardedPort] rows into connect-ready specs.
 * Throws IllegalStateException with a user-facing message for Dynamic,
 * unknown types, and out-of-range ports.
 */
fun resolveForwardSpecs(forwards: List<ForwardedPort>): List<ForwardSpec> {
    return forwards.mapIndexed { i, f ->
        val label = f.description.ifBlank { "rule #${i + 1}" }
        when (f.type) {
            "Local", "Remote" -> Unit
            "Dynamic" -> throw IllegalStateException(
                "Dynamic (SOCKS) forwarding ($label) is not supported on this " +
                    "device yet — it stays saved for desktop.",
            )
            else -> throw IllegalStateException(
                "Unknown forward type \"${f.type}\" ($label).",
            )
        }
        if (f.port !in 0..65535) {
            throw IllegalStateException(
                "Forward $label: listen port ${f.port} is out of range (0-65535).",
            )
        }
        if (f.targetPort !in 0..65535) {
            throw IllegalStateException(
                "Forward $label: target port ${f.targetPort} is out of range (0-65535).",
            )
        }
        ForwardSpec(
            type = f.type,
            host = f.host.ifBlank { "127.0.0.1" },
            port = f.port,
            targetAddress = f.targetAddress.ifBlank { "127.0.0.1" },
            targetPort = f.targetPort,
            description = label,
        )
    }
}

/** Desktop `ForwardedPort.toString` wording, ASCII arrow for device fonts. */
fun describeForward(s: ForwardSpec): String {
    return if (s.type == "Local") {
        "(local) ${s.host}:${s.port} -> (remote) ${s.targetAddress}:${s.targetPort}"
    } else {
        "(remote) ${s.host}:${s.port} -> (local) ${s.targetAddress}:${s.targetPort}"
    }
}

/**
 * Running forwards bound to one transport. [warnings] holds server-side
 * Remote rejections (session survives — the owner surfaces them, e.g. as an
 * in-terminal service line). [close] stops everything best-effort.
 */
class StartedForwards internal constructor(
    val warnings: List<String>,
    private val closers: List<() -> Unit>,
) : Closeable {
    override fun close() {
        closers.forEach { c -> try { c() } catch (_: Exception) { } }
    }

    companion object {
        fun empty() = StartedForwards(emptyList(), emptyList())
    }
}

/**
 * Open [specs] on an authenticated [SSHClient]. Local failures throw
 * (aborting the connect — the caller tears the client down); Remote
 * rejections land in [StartedForwards.warnings]. On throw, anything already
 * opened is closed so a half-forwarded transport never escapes.
 */
fun SSHClient.startPortForwards(
    specs: List<ForwardSpec>,
    onStage: (String) -> Unit = {},
): StartedForwards {
    if (specs.isEmpty()) return StartedForwards.empty()
    onStage("Opening port forwards...")
    val closers = mutableListOf<() -> Unit>()
    val warnings = mutableListOf<String>()
    fun shutdown() {
        closers.forEach { c -> try { c() } catch (_: Exception) { } }
    }
    try {
        for (s in specs) {
            val label = describeForward(s)
            when (s.type) {
                "Local" -> {
                    val server = try {
                        ServerSocket(s.port, 50, InetAddress.getByName(s.host))
                    } catch (e: IOException) {
                        throw IllegalStateException(
                            "Local forward $label failed: " +
                                "${e.message} (port busy or interface unavailable).",
                        )
                    }
                    val fwd = newLocalPortForwarder(
                        Parameters(s.host, s.port, s.targetAddress, s.targetPort),
                        server,
                    )
                    // listen() blocks — accept loop on a daemon thread. Bind
                    // errors already surfaced above; loop death just ends it.
                    thread(isDaemon = true, name = "ssh-local-forward-${s.port}") {
                        try { fwd.listen() } catch (_: Exception) { }
                    }
                    closers += {
                        try { fwd.close() } catch (_: Exception) { }
                        try { server.close() } catch (_: Exception) { }
                    }
                }
                "Remote" -> {
                    try {
                        val bound = remotePortForwarder.bind(
                            RemotePortForwarder.Forward(s.host, s.port),
                            SocketForwardingConnectListener(
                                InetSocketAddress(s.targetAddress, s.targetPort),
                            ),
                        )
                        val opener = remotePortForwarder
                        closers += { try { opener.cancel(bound) } catch (_: Exception) { } }
                    } catch (e: Exception) {
                        warnings +=
                            "Remote forward $label was rejected by the server " +
                                "(${e.message ?: e::class.simpleName}); " +
                                "the session stays connected."
                    }
                }
            }
        }
    } catch (e: Exception) {
        shutdown()
        throw e
    }
    return StartedForwards(warnings.toList(), closers.toList())
}
