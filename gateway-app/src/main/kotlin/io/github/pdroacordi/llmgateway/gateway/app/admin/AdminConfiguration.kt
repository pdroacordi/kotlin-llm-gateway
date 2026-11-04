package io.github.pdroacordi.llmgateway.gateway.app.admin

import io.github.pdroacordi.llmgateway.gateway.core.team.TeamAdminService
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.reactive.function.server.RouterFunction
import org.springframework.web.reactive.function.server.ServerResponse
import tools.jackson.databind.json.JsonMapper

@Configuration(proxyBeanMethods = false)
class AdminConfiguration {
    @Bean
    fun adminHandler(
        service: TeamAdminService,
        mapper: JsonMapper,
    ): AdminHandler = AdminHandler(service, mapper)

    @Bean
    fun adminRoutes(handler: AdminHandler): RouterFunction<ServerResponse> = AdminRoutes.routes(handler)
}
