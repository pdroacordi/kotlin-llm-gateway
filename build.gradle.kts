plugins {
    id("llmgw.coverage-merge")
}

// Every module with production code is listed here (ADR-014 checklist).
dependencies {
    kover(project(":gateway-core"))
    kover(project(":gateway-app"))
}

kover {
    reports {
        filters {
            excludes {
                // The `main` entry point runs under bootRun, not under tests.
                classes("io.github.pdroacordi.llmgateway.gateway.app.GatewayApplicationKt")
            }
        }
    }
}
