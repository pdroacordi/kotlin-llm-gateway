package io.github.pdroacordi.llmgateway.gateway.app.chat

import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class ChatErrorAdvice {
    @ExceptionHandler(IllegalStateException::class)
    fun handle(ex: IllegalStateException): String = "error"
}
