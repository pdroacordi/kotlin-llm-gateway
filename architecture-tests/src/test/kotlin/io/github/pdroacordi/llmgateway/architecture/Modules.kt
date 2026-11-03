package io.github.pdroacordi.llmgateway.architecture

const val BASE_PACKAGE = "io.github.pdroacordi.llmgateway"

enum class Role { CORE, ADAPTER, APP, CLIENT, TOOL }

data class Module(
    val name: String,
    val rootPackage: String,
    val role: Role,
    /** Modules whose packages this one may import (ARCH-9); its own root is always allowed. */
    val dependsOn: List<String> = emptyList(),
    /** Modules its `integrationTest` sources may import as well (ARCH-9, ADR-017). */
    val testDependsOn: List<String> = emptyList(),
) {
    /** First package segment under [BASE_PACKAGE]: `gateway`, `agent`, `cli`, ... */
    val family: String = rootPackage.removePrefix("$BASE_PACKAGE.").substringBefore('.')
}

const val TEST_SUPPORT = "test-support"

/**
 * Every target module, including those not created yet. Keep in sync with ADR-014; `dependsOn` follows the
 * module graph in CLAUDE.md, Architecture. Core tests are plain JUnit, so core modules get no `testDependsOn`.
 */
object Modules {
    private val fixtures = listOf(TEST_SUPPORT)

    val all: List<Module> =
        listOf(
            Module("gateway-core", "$BASE_PACKAGE.gateway.core", Role.CORE),
            Module(
                "gateway-providers",
                "$BASE_PACKAGE.gateway.providers",
                Role.ADAPTER,
                listOf("gateway-core"),
                fixtures,
            ),
            Module("gateway-infra", "$BASE_PACKAGE.gateway.infra", Role.ADAPTER, listOf("gateway-core"), fixtures),
            Module(
                "gateway-app",
                "$BASE_PACKAGE.gateway.app",
                Role.APP,
                listOf("gateway-core", "gateway-providers", "gateway-infra"),
                fixtures,
            ),
            Module("agent-core", "$BASE_PACKAGE.agent.core", Role.CORE),
            Module("agent-app", "$BASE_PACKAGE.agent.app", Role.APP, listOf("agent-core"), fixtures),
            Module("cli", "$BASE_PACKAGE.cli", Role.CLIENT, testDependsOn = fixtures),
            Module("mock-provider", "$BASE_PACKAGE.mockprovider", Role.APP),
            Module("evals", "$BASE_PACKAGE.evals", Role.TOOL),
            Module("architecture-tests", "$BASE_PACKAGE.architecture", Role.TOOL),
            Module(TEST_SUPPORT, "$BASE_PACKAGE.testsupport", Role.TOOL),
        )
}

fun List<Module>.named(name: String): Module? = firstOrNull { it.name == name }
