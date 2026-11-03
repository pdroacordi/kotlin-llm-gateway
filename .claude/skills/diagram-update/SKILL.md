---
name: diagram-update
description: Create or update kotlin-llm-gateway's Mermaid diagrams in docs/diagrams/ (container overview, request and run sequences, state machines, data model) when a flow, the module graph or the schema changes. Use when an item changes the request pipeline, a sequence between services, the run/step state machine or a migration, and when the user says "atualiza o diagrama", "update the diagram", "draw the flow".
---

# diagram-update

Diagrams are Mermaid inside Markdown, one diagram per file in `docs/diagrams/`. GitHub renders them, so there is no exported SVG to keep in sync. Conventions are in [`docs/diagrams/README.md`](../../../docs/diagrams/README.md).

## When

- The request pipeline changes (a stage added, moved or made conditional): `sequence-chat-completion.md`.
- A new service, store or broker, or a dependency between modules: `container-overview.md`.
- The agent run lifecycle or its states: `sequence-agent-run.md`, and a `state-*.md` when a state machine exists.
- A Flyway migration adds or changes tables: `er-<area>.md`.
- A new flow worth a picture (fallback, budget downgrade, streaming guardrails): a new file, linked from the README index.

## How

1. Read the current file and the code it describes; diagram what is built. A planned part is drawn dashed or noted "planned (ID)".
2. Edit the Mermaid block. Keep participant and node names equal to code names (`RateLimiterPort`, `gateway-app`), not prose.
3. Validate the syntax when Node is available: `npx -y @mermaid-js/mermaid-cli -i docs/diagrams/<file>.md -o <scratchpad>/check.md`. A parse error is a failed step.
4. Update the `Updated` line under the title and the index table in `docs/diagrams/README.md`.
5. Mention the diagram in the spec log entry.

Keep each diagram readable on one screen. Past about 15 participants or 25 nodes, split it.
