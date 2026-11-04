package io.github.pdroacordi.llmgateway.gateway.app.config

import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/** A hung peer on loopback: it accepts a connection and never answers, as a frozen database does. */
class BlackHoleServer : AutoCloseable {
    private val socket = ServerSocket(0, BACKLOG, InetAddress.getLoopbackAddress())
    private val accepted = CopyOnWriteArrayList<Socket>()

    val port: Int = socket.localPort

    init {
        thread(isDaemon = true, name = "black-hole-server") {
            try {
                while (true) accepted += socket.accept()
            } catch (_: IOException) {
                // close() ended the accept.
            }
        }
    }

    override fun close() {
        socket.close()
        accepted.forEach(Socket::close)
    }

    private companion object {
        const val BACKLOG = 16
    }
}
