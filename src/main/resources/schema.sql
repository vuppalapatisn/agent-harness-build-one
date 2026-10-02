CREATE TABLE IF NOT EXISTS harness_session (
    id          VARCHAR(36)  PRIMARY KEY,
    user_id     VARCHAR(128) NOT NULL,
    created_at  TIMESTAMP    NOT NULL
);

CREATE TABLE IF NOT EXISTS harness_message (
    session_id  VARCHAR(36)      NOT NULL REFERENCES harness_session (id),
    seq         INTEGER          NOT NULL,
    role        VARCHAR(16)      NOT NULL,
    payload     VARCHAR(1048576) NOT NULL,
    created_at  TIMESTAMP        NOT NULL,
    PRIMARY KEY (session_id, seq)
);
