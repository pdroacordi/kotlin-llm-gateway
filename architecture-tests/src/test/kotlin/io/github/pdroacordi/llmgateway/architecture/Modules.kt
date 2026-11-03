package io.github.pdroacordi.llmgateway.architecture

const val BASE_PACKAGE = "io.github.pdroacordi.llmgateway"

enum class Role { CORE, ADAPTER, APP, CLIENT, TOOL }

data class Module(
    val name: String,
    val rootPackage: String,
    val role: Role,
    /** Modules whose packages this one may import (ARCH-9); its own root is always allowed. */
    val dependsOn: List<String> = emptyList(),
) {
    /** First package segment under [BASE_PACKAGE]: `gateway`, `agent`, `cli`, ... */
    val family: String = rootPackage.removePrefix("$BASE_PACKAGE.").substringBefore('.')
}

/**
 * Every target module, including those not created yet. Keep in sync with ADR-014; `dependsOn` follows the
 * module graph in CLAUDE.md, Architecture.
 */
object Modules {
    val all: List<Module> =
        listOf(
            Module("gateway-core", "$BASE_PACKAGE.gateway.core", Role.CORE),
            Module("gateway-providers", "$BASE_PACKAGE.gateway.providers", Role.ADAPTER, listOf("gateway-core")),
            Module("gateway-infra", "$BASE_PACKAGE.gateway.infra", Role.ADAPTER, listOf("gateway-core")),
            Module(
                "gateway-app",
                "$BASE_PACKAGE.gateway.app",
                Role.APP,
                listOf("gateway-core", "gateway-providers", "gateway-infra"),
            ),
            Module("agent-core", "$BASE_PACKAGE.agent.core", Role.CORE),
            Module("agent-app", "$BASE_PACKAGE.agent.app", Role.APP, listOf("agent-core")),
            Module("cli", "$BASE_PACKAGE.cli", Role.CLIENT),
            Module("mock-provider", "$BASE_PACKAGE.mockprovider", Role.TOOL),
            Module("evals", "$BASE_PACKAGE.evals", Role.TOOL),
            Module("architecture-tests", "$BASE_PACKAGE.architecture", Role.TOOL),
        )
}

fun List<Module>.named(name: String): Module? = firstOrNull { it.name == name }
