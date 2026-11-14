-- An explicit intent survives failures between the engine, filesystem and database.
ALTER TABLE local_file ADD COLUMN deletion_requested BOOLEAN NOT NULL DEFAULT false;
