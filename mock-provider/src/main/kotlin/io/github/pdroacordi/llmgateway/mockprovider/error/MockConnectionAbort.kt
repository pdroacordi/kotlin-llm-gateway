package io.github.pdroacordi.llmgateway.mockprovider.error

/**
 * Signalled by a body publisher after the response is committed, so that Reactor Netty closes the connection without
 * the terminating chunk and the client sees a premature close (`disconnect` fault, a `stall` at `mock.max-hold`).
 * Intentional, so the mapper logs it at DEBUG, decided by this type (FND-03 section 5, Faults). Also what the mapper
 * returns after logging a failure on a committed response: stackless, so no checkpoint rides on it to Reactor Netty.
 */
class MockConnectionAbort(
    reason: String,
) : RuntimeException(reason, null, false, false)
