CREATE TABLE integration_settings (
    integration_id varchar(32) PRIMARY KEY,
    encrypted_config text NOT NULL
);
