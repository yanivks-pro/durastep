CREATE SCHEMA demo;

CREATE TABLE demo.effects (
    idempotency_key text        PRIMARY KEY,
    workflow_id     text        NOT NULL,
    effect          text        NOT NULL,
    repeats         integer     NOT NULL DEFAULT 0,
    applied_at      timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX effects_workflow_idx ON demo.effects (workflow_id, effect);
