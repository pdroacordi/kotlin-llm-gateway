package io.github.pdroacordi.llmgateway.gateway.core.team

import io.github.pdroacordi.llmgateway.gateway.core.error.Unauthenticated
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class OperatorAuthenticatorTest {
    private val operator = OperatorAuthenticator(TestKeys.hasher(), TestKeys.ADMIN_KEY)

    @Test
    fun `the right value is accepted`() {
        assertNull(operator.check(listOf(TestKeys.ADMIN_BEARER)))
    }

    @Test
    fun `a value of the wrong length is invalid`() {
        assertEquals(Unauthenticated.InvalidKey, operator.check(listOf("Bearer short")))
    }

    @Test
    fun `a wrong value of the right length is invalid`() {
        val wrong = TestKeys.ADMIN_KEY.dropLast(1) + "9"

        assertEquals(Unauthenticated.InvalidKey, operator.check(listOf("Bearer $wrong")))
    }

    @Test
    fun `a team key is invalid`() {
        assertEquals(Unauthenticated.InvalidKey, operator.check(listOf(TestKeys.BEARER)))
    }

    @Test
    fun `no credential is missing`() {
        assertEquals(Unauthenticated.MissingKey, operator.check(emptyList()))
        assertEquals(Unauthenticated.MissingKey, operator.check(listOf("Basic abc")))
    }

    @Test
    fun `two lines are invalid, even when both hold the admin key`() {
        assertEquals(
            Unauthenticated.InvalidKey,
            operator.check(listOf(TestKeys.ADMIN_BEARER, TestKeys.ADMIN_BEARER)),
        )
    }
}
