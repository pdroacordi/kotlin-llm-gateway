package io.github.pdroacordi.llmgateway.gateway.app.chat

import org.springframework.web.server.WebHandler

class WrappedWebHandler {
    fun wrap(handler: WebHandler) = org.springframework.web.server.handler.ExceptionHandlingWebHandler(handler, emptyList())
}
