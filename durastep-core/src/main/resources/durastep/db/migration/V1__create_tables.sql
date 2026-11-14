CREATE TABLE workflows (
    id               text        PRIMARY KEY,
    type             text        NOT NULL,
    state            text        NOT NULL,
    blocked          boolean     NOT NULL DEFAULT false,
    blocked_error    jsonb,
    status           text,
    input            jsonb,
    result           jsonb,
    failure          jsonb,
    cancel_requested boolean     NOT NULL DEFAULT false,
    rerun_of         text        REFERENCES workflows (id) ON DELETE SET NULL,
    history_length   integer     NOT NULL DEFAULT 0,
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now(),
    closed_at        timestamptz,
    deadline_at      timestamptz,

    CONSTRAINT workflows_id_length CHECK (char_length(id) BETWEEN 1 AND 255),
    CONSTRAINT workflows_state_valid CHECK (state IN ('RUNNING', 'COMPLETED', 'FAILED', 'CANCELLED')),
    CONSTRAINT workflows_closed_iff_final CHECK ((state = 'RUNNING') = (closed_at IS NULL)),
    CONSTRAINT workflows_blocked_only_running CHECK (NOT blocked OR state = 'RUNNING'),
    CONSTRAINT workflows_history_length_non_negative CHECK (history_length >= 0)
);

CREATE INDEX workflows_state_type_idx ON workflows (state, type);
CREATE INDEX workflows_blocked_idx ON workflows (id) WHERE blocked;
CREATE INDEX workflows_closed_at_idx ON workflows (closed_at) WHERE closed_at IS NOT NULL;

CREATE TABLE history_events (
    workflow_id text        NOT NULL REFERENCES workflows (id) ON DELETE CASCADE,
    seq         integer     NOT NULL,
    event_type  text        NOT NULL,
    payload     jsonb,
    created_at  timestamptz NOT NULL DEFAULT now(),

    PRIMARY KEY (workflow_id, seq),
    CONSTRAINT history_events_seq_positive CHECK (seq >= 1)
);

CREATE TABLE tasks (
    id            bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    workflow_id   text        NOT NULL REFERENCES workflows (id) ON DELETE CASCADE,
    kind          text        NOT NULL,
    queue         text        NOT NULL DEFAULT 'default',
    activity_seq  integer,
    attempt       integer     NOT NULL DEFAULT 1,
    visible_at    timestamptz NOT NULL DEFAULT now(),
    lease_owner   text,
    lease_until   timestamptz,
    fencing_token bigint      NOT NULL DEFAULT 0,
    needs_rerun   boolean     NOT NULL DEFAULT false,
    last_error    jsonb,

    CONSTRAINT tasks_kind_valid CHECK (kind IN ('WORKFLOW', 'ACTIVITY')),
    CONSTRAINT tasks_activity_seq_iff_activity CHECK ((kind = 'ACTIVITY') = (activity_seq IS NOT NULL)),
    CONSTRAINT tasks_attempt_positive CHECK (attempt >= 1),
    CONSTRAINT tasks_lease_complete CHECK ((lease_owner IS NULL) = (lease_until IS NULL))
);

CREATE INDEX tasks_claim_idx ON tasks (queue, kind, visible_at);
CREATE UNIQUE INDEX tasks_one_decider_per_workflow ON tasks (workflow_id) WHERE kind = 'WORKFLOW';
CREATE UNIQUE INDEX tasks_one_task_per_activity ON tasks (workflow_id, activity_seq) WHERE kind = 'ACTIVITY';

CREATE TABLE timers (
    id          bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    workflow_id text        NOT NULL REFERENCES workflows (id) ON DELETE CASCADE,
    seq         integer     NOT NULL,
    fire_at     timestamptz NOT NULL,

    CONSTRAINT timers_one_per_step UNIQUE (workflow_id, seq)
);

CREATE INDEX timers_fire_at_idx ON timers (fire_at);

CREATE TABLE inbox (
    id          bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    workflow_id text        NOT NULL REFERENCES workflows (id) ON DELETE CASCADE,
    kind        text        NOT NULL,
    name        text,
    payload     jsonb,
    request_id  text,
    received_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT inbox_kind_valid CHECK (kind IN ('SIGNAL', 'ACTIVITY_COMPLETED', 'ACTIVITY_FAILED', 'TIMER_FIRED', 'CANCEL_REQUESTED')),
    CONSTRAINT inbox_request_once UNIQUE (workflow_id, request_id)
);

CREATE INDEX inbox_workflow_order_idx ON inbox (workflow_id, id);
