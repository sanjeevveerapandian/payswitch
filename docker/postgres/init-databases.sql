-- Runs once, the first time the Postgres container starts with an empty data folder.
-- One database per service: each service owns its data and never reads another's tables.
CREATE DATABASE banka;
CREATE DATABASE bankb;
CREATE DATABASE switch;
