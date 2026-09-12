package id.web.izs.sshclient.core.ssh

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import javax.net.SocketFactory

/**
 * Outbound SOCKS proxy for the SSH transport — desktop `socksProxy` parity
 * (tabby-ssh/src/session/ssh.ts:392-399, `newSocksProxy`, default port 1080).
 *
 * sshj has no SOCKS knob of its own; it connects whatever
 * [javax.net.SocketFactory] it is given, so a factory minting
 * `Socket(Proxy(SOCKS, …))` routes the whole SSH session through the proxy.
 * Pure JVM — unit-testable against a fake SOCKS5 server.
 */
object SocksProxy {

    /** Desktop default SOCKS port (ssh.ts: `socksProxyPort ?? 1080`). */
    const val DEFAULT_PORT = 1080

    /**
     * Resolve profile fields to a proxy address, or null for direct.
     * Blank host = direct (mode inference needs a host anyway); blank/zero
     * port = [DEFAULT_PORT]. Anything else out of range throws with a
     * user-facing message.
     */
    fun resolveAddress(host: String?, port: Int?): InetSocketAddress? {
        if (host.isNullOrBlank()) return null
        val p = if (port == null || port <= 0) DEFAULT_PORT else port
        if (p !in 1..65535) {
            throw IllegalStateException("SOCKS proxy port $port is out of range (1-65535).")
        }
        return InetSocketAddress(host, p)
    }

    /**
     * SocketFactory whose sockets connect THROUGH [proxy]. sshj calls the
     * no-arg [createSocket] and connects it later, but every overload routes
     * through the proxy so no call path leaks a direct socket.
     */
    fun socketFactory(proxy: InetSocketAddress): SocketFactory =
        object : SocketFactory() {
            private fun proxied(): Socket =
                Socket(Proxy(Proxy.Type.SOCKS, proxy))

            override fun createSocket(): Socket = proxied()

            override fun createSocket(host: String, port: Int): Socket = proxied()

            override fun createSocket(
                host: String,
                port: Int,
                localHost: InetAddress,
                localPort: Int,
            ): Socket = proxied().apply { bind(InetSocketAddress(localHost, localPort)) }

            override fun createSocket(host: InetAddress, port: Int): Socket = proxied()

            override fun createSocket(
                address: InetAddress,
                port: Int,
                localAddress: InetAddress,
                localPort: Int,
            ): Socket = proxied().apply { bind(InetSocketAddress(localAddress, localPort)) }
        }
}
