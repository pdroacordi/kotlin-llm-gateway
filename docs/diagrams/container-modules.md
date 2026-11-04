# Module graph

Updated: 2026-11-04 (built modules; GW-04 added `gateway-infra`)

```mermaid
flowchart LR
    app["gateway-app"] --> core["gateway-core"]
    app --> providers["gateway-providers"]
    app --> infra["gateway-infra"]
    providers --> core
    infra --> core
    arch["architecture-tests"] -.->|"scans sources"| app
    support["test-support"] -.->|"integrationTest only"| app
    support -.->|"integrationTest only"| providers
    support -.->|"integrationTest only"| infra
    mock["mock-provider"] -.->|"image for tests"| support
    agentapp["agent-app, planned (AGT-01)"] -.-> agentcore["agent-core, planned (AGT-01)"]
    agentapp -.->|"HTTP"| app
    cli["cli, planned (CLI-01)"] -.->|"HTTP"| app
    cli -.->|"HTTP"| agentapp
```

- A solid arrow is a compile dependency, and it points inward (ADR-005). A dashed one is a test, tool or HTTP relation, or a planned module.
- `TeamStoreConfiguration` is the only `main` code in `gateway-app` naming `.gateway.infra` (ARCH-12).
- `gateway-core`'s `testFixtures` (`InMemoryTeamStore`, `TeamStoreContract`, `TestKeys`) serve the test suites of `gateway-app`, `gateway-providers` and `gateway-infra`.
- `mock-provider` and `test-support` import nothing of ours.
