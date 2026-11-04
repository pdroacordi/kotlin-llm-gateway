plugins {
    id("llmgw.coverage-merge")
}

// Every module with production code is listed here (ADR-014 checklist).
dependencies {
    kover(project(":gateway-core"))
    kover(project(":gateway-providers"))
    kover(project(":gateway-infra"))
    kover(project(":gateway-app"))
    kover(project(":mock-provider"))
}

kover {
    reports {
        filters {
            excludes {
                // The `main` entry points run under bootRun, not under tests.
                classes(
                    "io.github.pdroacordi.llmgateway.gateway.app.GatewayApplicationKt",
                    "io.github.pdroacordi.llmgateway.mockprovider.MockProviderApplicationKt",
                )
            }
        }
    }
}
