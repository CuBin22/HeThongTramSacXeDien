-- Run last after files 01-05 when installing manually into an empty database.
-- This records the manually installed schema as Flyway version 23 so the backend
-- does not replay migrations V1-V23. Later migrations still run normally.
CREATE TABLE flyway_schema_history (
    installed_rank integer NOT NULL,
    version varchar(50),
    description varchar(200) NOT NULL,
    type varchar(20) NOT NULL,
    script varchar(1000) NOT NULL,
    checksum integer,
    installed_by varchar(100) NOT NULL,
    installed_on timestamp without time zone NOT NULL DEFAULT now(),
    execution_time integer NOT NULL,
    success boolean NOT NULL,
    CONSTRAINT flyway_schema_history_pk PRIMARY KEY (installed_rank)
);
CREATE INDEX flyway_schema_history_s_idx ON flyway_schema_history (success);
INSERT INTO flyway_schema_history (
    installed_rank, version, description, type, script, checksum,
    installed_by, execution_time, success
) VALUES (
    1, '23', '<< Flyway Baseline >>', 'BASELINE', '<< Flyway Baseline >>', NULL,
    current_user, 0, true
);
