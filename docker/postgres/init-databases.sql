-- Runs once, the first time the Postgres container starts with an empty data folder.
-- One database per service (banka, bankb, bankc, switch): each service owns its data and never reads another's tables.
CREATE DATABASE banka;
CREATE DATABASE bankb;
CREATE DATABASE switch;
CREATE DATABASE bankc;
