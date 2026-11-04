# Data model: teams and keys

Updated: 2026-11-04 (built, GW-04)

```mermaid
erDiagram
    teams ||--o{ team_keys : owns
    teams {
        uuid id PK "UUIDv7, from core"
        text name UK
        timestamptz created_at
    }
    team_keys {
        uuid id PK "UUIDv7, from core"
        uuid team_id FK
        text name "unique per team among unrevoked keys"
        bytea digest UK "HMAC-SHA-256, 32 bytes"
        smallint secret_version
        text hint "first 10 characters"
        timestamptz created_at
        timestamptz expires_at "null: never"
        timestamptz revoked_at "null: active"
    }
```

- From `V1__teams_and_keys.sql`. `flyway_schema_history` is left out.
- Rows are never deleted. IDs, times and digests come from core; the database generates nothing.
- `team_keys_digest_key` serves the hot-path lookup, `team_keys_team_name_active` is the partial unique index on `(team_id, name) WHERE revoked_at IS NULL`, and `team_keys_team_id_id` serves keyset listing.
- `secret_version` is written; reading two versions is GW-12.
