package io.github.pdroacordi.llmgateway.testsupport

import reactor.blockhound.BlockHound
import reactor.blockhound.integration.BlockHoundIntegration

/**
 * r2dbc-postgresql builds its SCRAM client on the driver's event loop, with a nonce from a `SecureRandom` no caller can
 * replace. On Linux that reads `/dev/urandom`, which never waits, so only this call is allowed (GW-04 Log, spike S4).
 */
class ScramNonceBlockHoundIntegration : BlockHoundIntegration {
    override fun applyTo(builder: BlockHound.Builder) {
        builder.allowBlockingCallsInside("com.ongres.scram.common.ScramFunctions", "nonce")
    }
}
