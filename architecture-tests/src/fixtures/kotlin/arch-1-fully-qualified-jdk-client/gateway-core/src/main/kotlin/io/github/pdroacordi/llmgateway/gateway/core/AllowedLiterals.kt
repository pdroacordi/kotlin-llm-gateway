package io.github.pdroacordi.llmgateway.gateway.core

// Each line would be a false positive if a string or a comment were scanned as code.
val quote = '"'
val afterQuote = "java.net.Socket"
val escaped = '\''
val url = "http://example.test" // java.net.Socket
val raw = """
    java.net.Socket /* "
"""
val slash = "/*" + "*/" // java.sql.Connection

/* A block comment holds "java.net.Socket and an open quote. */
class AllowedLiterals {
    fun `a name with a " quote`() = Unit

    val label = "java.net.Socket"
}
