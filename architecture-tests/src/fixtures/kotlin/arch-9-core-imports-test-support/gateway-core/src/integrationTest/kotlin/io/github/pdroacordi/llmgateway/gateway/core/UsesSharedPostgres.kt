package io.github.pdroacordi.llmgateway.gateway.core

import io.github.pdroacordi.llmgateway.testsupport.SharedPostgres

class UsesSharedPostgres {
    val url = SharedPostgres.container.jdbcUrl
}
