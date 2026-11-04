CREATE TABLE teams (
    id         uuid        PRIMARY KEY,
    name       text        NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT teams_name_key UNIQUE (name)
);

CREATE TABLE team_keys (
    id             uuid        PRIMARY KEY,
    team_id        uuid        NOT NULL REFERENCES teams (id),
    name           text        NOT NULL,
    digest         bytea       NOT NULL,
    secret_version smallint    NOT NULL,
    hint           text        NOT NULL,
    created_at     timestamptz NOT NULL,
    expires_at     timestamptz,
    revoked_at     timestamptz,
    CONSTRAINT team_keys_digest_key UNIQUE (digest),
    CONSTRAINT team_keys_digest_length CHECK (octet_length(digest) = 32)
);

CREATE UNIQUE INDEX team_keys_team_name_active ON team_keys (team_id, name) WHERE revoked_at IS NULL;
CREATE INDEX team_keys_team_id_id ON team_keys (team_id, id);
