# Diagrams

Mermaid inside Markdown, one diagram per file. GitHub renders them; there is no exported image to keep in sync. Updated through the `diagram-update` skill.

## Conventions

- File names by kind: `container-*.md` (services and stores), `sequence-*.md` (interactions over time), `state-*.md` (state machines), `er-*.md` (tables).
- Each file: a title, an `Updated:` line with the date and roadmap ID, the diagram, and at most a few lines of notes.
- Names in diagrams equal code names (`RateLimiterPort`, `gateway-app`), not prose.
- A part that is planned and not built is drawn dashed or marked "planned (ID)". Today every diagram shows the target design; items replace the target with what is built as they land.
- One screen per diagram. Past about 15 participants or 25 nodes, split it.

## Index

| Diagram | Shows | Updated |
|---|---|---|
| [container-overview](container-overview.md) | Services, stores and external providers | 2026-11-03 (target) |
| [sequence-chat-completion](sequence-chat-completion.md) | The request pipeline of `/v1/chat/completions` | 2026-11-03 (target) |
| [sequence-agent-run](sequence-agent-run.md) | An agent run with tool calls, persisted step by step | 2026-11-03 (target) |
