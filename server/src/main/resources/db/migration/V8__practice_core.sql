-- 答题、判分、申诉与 FSRS 复习（DATA-MODEL §5.8–§5.13 / §12.1）。
-- attempts 只存提交，attempt_gradings 每次判分 append-only；同一 attemptId 由主键保证幂等。
CREATE TABLE attempts (
    id                  CHAR(36)    NOT NULL,
    user_id             BIGINT      NOT NULL,
    device_id           VARCHAR(64) NULL,
    sentence_id         CHAR(26)    NOT NULL,
    sentence_version_id BIGINT      NOT NULL,
    question_version    INT         NOT NULL,
    mode                VARCHAR(16) NOT NULL,
    kind                VARCHAR(16) NOT NULL,
    retry_of_id         CHAR(36)    NULL,
    answer              JSON        NOT NULL,
    answer_hash         CHAR(64)    NOT NULL,
    hints_used          SMALLINT    NOT NULL DEFAULT 0,
    duration_ms         INT         NULL,
    client_created_at   DATETIME(6) NULL,
    created_at          DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_attempt_user_created (user_id, created_at),
    KEY idx_attempt_user_sentence (user_id, sentence_id, mode, created_at),
    KEY idx_attempt_version (sentence_version_id),
    CONSTRAINT fk_attempt_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_attempt_sentence FOREIGN KEY (sentence_id) REFERENCES sentences (id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_attempt_version FOREIGN KEY (sentence_version_id)
        REFERENCES sentence_versions (id) ON DELETE RESTRICT,
    CONSTRAINT fk_attempt_retry FOREIGN KEY (retry_of_id) REFERENCES attempts (id)
        ON DELETE RESTRICT,
    CONSTRAINT chk_attempt_mode CHECK (mode IN ('CLOZE', 'FREE')),
    CONSTRAINT chk_attempt_kind CHECK (kind IN ('FIRST', 'RETRY', 'REVEAL')),
    CONSTRAINT chk_attempt_hints CHECK (hints_used BETWEEN 0 AND 3),
    CONSTRAINT chk_attempt_duration
        CHECK (duration_ms IS NULL OR duration_ms BETWEEN 0 AND 86400000)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE attempt_gradings (
    id                 BIGINT       NOT NULL AUTO_INCREMENT,
    attempt_id         CHAR(36)     NOT NULL,
    sequence           INT          NOT NULL,
    grader             VARCHAR(16)  NOT NULL,
    score              INT          NULL,
    max_score          INT          NOT NULL DEFAULT 100,
    result             JSON         NOT NULL,
    normalizer_version VARCHAR(16)  NOT NULL,
    rubric_version     VARCHAR(16)  NULL,
    prompt_version_id  BIGINT       NULL,
    model_snapshot     VARCHAR(128) NULL,
    latency_ms         INT          NULL,
    created_at         DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_grading_attempt_sequence (attempt_id, sequence),
    KEY idx_grading_created (created_at),
    KEY idx_grading_grader (grader),
    CONSTRAINT fk_grading_attempt FOREIGN KEY (attempt_id) REFERENCES attempts (id)
        ON DELETE RESTRICT,
    CONSTRAINT chk_grading_grader CHECK (grader IN ('LOCAL', 'GRAMMAR', 'MODEL', 'HUMAN')),
    CONSTRAINT chk_grading_score CHECK (score IS NULL OR score BETWEEN 0 AND 100),
    CONSTRAINT chk_grading_max_score CHECK (max_score = 100)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE appeals (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    attempt_id       CHAR(36)     NOT NULL,
    annotation_key   VARCHAR(64)  NOT NULL,
    sentence_id      CHAR(26)     NOT NULL,
    chunk_index      INT          NOT NULL,
    user_id          BIGINT       NOT NULL,
    kind             VARCHAR(16)  NOT NULL,
    status           VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    proposed_answer  VARCHAR(255) NULL,
    reason           VARCHAR(500) NULL,
    decision         JSON         NULL,
    decision_reason  VARCHAR(500) NULL,
    decided_by       VARCHAR(16)  NULL,
    decided_at       DATETIME(6)  NULL,
    model_snapshot   VARCHAR(128) NULL,
    prompt_version_id BIGINT      NULL,
    created_at       DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at       DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_appeal_annotation (attempt_id, annotation_key, kind),
    KEY idx_appeal_overlay (sentence_id, status, chunk_index),
    KEY idx_appeal_user_status (user_id, status),
    CONSTRAINT fk_appeal_attempt FOREIGN KEY (attempt_id) REFERENCES attempts (id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_appeal_sentence FOREIGN KEY (sentence_id) REFERENCES sentences (id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_appeal_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT chk_appeal_kind CHECK (kind IN ('APPEAL', 'DISPUTE')),
    CONSTRAINT chk_appeal_status
        CHECK (status IN ('PENDING', 'ACCEPTED', 'REJECTED', 'WITHDRAWN'))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE reviews (
    id                      BIGINT       NOT NULL AUTO_INCREMENT,
    user_id                 BIGINT       NOT NULL,
    sentence_id             CHAR(26)     NOT NULL,
    mode                    VARCHAR(16)  NOT NULL,
    state                   VARCHAR(16)  NOT NULL DEFAULT 'NEW',
    fsrs_state              VARCHAR(16)  NOT NULL DEFAULT 'NEW',
    due_at                  DATETIME(6)  NOT NULL,
    stability               DOUBLE       NOT NULL DEFAULT 0,
    difficulty              DOUBLE       NOT NULL DEFAULT 0,
    reps                    INT          NOT NULL DEFAULT 0,
    lapses                  INT          NOT NULL DEFAULT 0,
    elapsed_days            INT          NOT NULL DEFAULT 0,
    scheduled_days          INT          NOT NULL DEFAULT 0,
    last_rating             SMALLINT     NULL,
    last_review_at          DATETIME(6)  NULL,
    last_attempt_id         CHAR(36)     NULL,
    last_weights_version_id BIGINT       NULL,
    created_at              DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at              DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_review_card (user_id, sentence_id, mode),
    KEY idx_review_due (user_id, due_at),
    KEY idx_review_state_due (user_id, state, due_at),
    CONSTRAINT fk_review_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_review_sentence FOREIGN KEY (sentence_id) REFERENCES sentences (id)
        ON DELETE RESTRICT,
    CONSTRAINT chk_review_state CHECK (state IN ('NEW', 'LEARNING', 'MASTERED')),
    CONSTRAINT chk_review_fsrs_state
        CHECK (fsrs_state IN ('NEW', 'LEARNING', 'REVIEW', 'RELEARNING')),
    CONSTRAINT chk_review_last_rating CHECK (last_rating IS NULL OR last_rating BETWEEN 1 AND 4)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE fsrs_parameter_versions (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    owner_type      VARCHAR(8)   NOT NULL,
    user_id         BIGINT       NULL,
    algorithm       VARCHAR(16)  NOT NULL DEFAULT 'FSRS-6',
    weights         JSON         NOT NULL,
    sample_count    INT          NOT NULL DEFAULT 0,
    status          VARCHAR(16)  NOT NULL DEFAULT 'CANDIDATE',
    metrics         JSON         NULL,
    optimized_at    DATETIME(6)  NULL,
    created_by      BIGINT       NULL,
    reason          VARCHAR(255) NULL,
    created_at      DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    active_owner_key VARCHAR(32)
        GENERATED ALWAYS AS (
            IF(status = 'ACTIVE', CONCAT(owner_type, ':', IFNULL(user_id, 0)), NULL)
        ) STORED,
    PRIMARY KEY (id),
    UNIQUE KEY uk_fsrs_active_owner (active_owner_key),
    KEY idx_fsrs_owner_status (owner_type, user_id, status, created_at),
    CONSTRAINT fk_fsrs_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT chk_fsrs_owner_type CHECK (owner_type IN ('GLOBAL', 'USER')),
    CONSTRAINT chk_fsrs_status CHECK (status IN ('CANDIDATE', 'ACTIVE', 'RETIRED')),
    CONSTRAINT chk_fsrs_global_user CHECK (owner_type = 'USER' OR user_id IS NULL)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE review_logs (
    id                  BIGINT      NOT NULL AUTO_INCREMENT,
    review_id           BIGINT      NOT NULL,
    user_id             BIGINT      NOT NULL,
    sentence_id         CHAR(26)    NOT NULL,
    mode                VARCHAR(16) NOT NULL,
    attempt_id          CHAR(36)    NULL,
    rating              SMALLINT    NOT NULL,
    state_before        VARCHAR(16) NOT NULL,
    state_after         VARCHAR(16) NOT NULL,
    due_before          DATETIME(6) NULL,
    due_after           DATETIME(6) NOT NULL,
    stability_before    DOUBLE      NULL,
    stability_after     DOUBLE      NOT NULL,
    difficulty_before   DOUBLE      NULL,
    difficulty_after    DOUBLE      NOT NULL,
    elapsed_days        INT         NOT NULL,
    scheduled_days      INT         NOT NULL,
    review_at           DATETIME(6) NOT NULL,
    duration_ms         INT         NULL,
    weights_version_id  BIGINT      NOT NULL,
    created_at          DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_review_log_attempt (attempt_id),
    KEY idx_review_log_user_time (user_id, review_at),
    KEY idx_review_log_review (review_id, review_at),
    KEY idx_review_log_sentence (sentence_id, mode),
    CONSTRAINT fk_review_log_review FOREIGN KEY (review_id) REFERENCES reviews (id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_review_log_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_review_log_sentence FOREIGN KEY (sentence_id) REFERENCES sentences (id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_review_log_attempt FOREIGN KEY (attempt_id) REFERENCES attempts (id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_review_log_weights FOREIGN KEY (weights_version_id)
        REFERENCES fsrs_parameter_versions (id) ON DELETE RESTRICT,
    CONSTRAINT chk_review_log_rating CHECK (rating BETWEEN 1 AND 4),
    CONSTRAINT chk_review_log_state_before
        CHECK (state_before IN ('NEW', 'LEARNING', 'REVIEW', 'RELEARNING')),
    CONSTRAINT chk_review_log_state_after
        CHECK (state_after IN ('NEW', 'LEARNING', 'REVIEW', 'RELEARNING'))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

-- 只增不改：判分、提交与复习日志不能原地 UPDATE/DELETE；参数版本的 status 允许受控变更。
CREATE TRIGGER trg_attempts_no_update
    BEFORE UPDATE
    ON attempts
    FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'attempts is append-only';

CREATE TRIGGER trg_attempts_no_delete
    BEFORE DELETE
    ON attempts
    FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'attempts is append-only';

CREATE TRIGGER trg_attempt_gradings_no_update
    BEFORE UPDATE
    ON attempt_gradings
    FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'attempt_gradings is append-only';

CREATE TRIGGER trg_attempt_gradings_no_delete
    BEFORE DELETE
    ON attempt_gradings
    FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'attempt_gradings is append-only';

CREATE TRIGGER trg_review_logs_no_update
    BEFORE UPDATE
    ON review_logs
    FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'review_logs is append-only';

CREATE TRIGGER trg_review_logs_no_delete
    BEFORE DELETE
    ON review_logs
    FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'review_logs is append-only';

