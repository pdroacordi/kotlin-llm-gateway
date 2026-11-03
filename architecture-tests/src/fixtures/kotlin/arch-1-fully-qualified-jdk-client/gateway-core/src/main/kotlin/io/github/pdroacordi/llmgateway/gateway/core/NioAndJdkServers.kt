package io.github.pdroacordi.llmgateway.gateway.core

import java.nio.channels.SocketChannel

class NioAndJdkServers(val channel: SocketChannel) {
    val server = com.sun.net.httpserver.HttpServer.create()
    val option = jdk.net.ExtendedSocketOptions.TCP_KEEPIDLE
}
