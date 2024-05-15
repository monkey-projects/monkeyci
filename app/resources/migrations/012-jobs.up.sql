CREATE TABLE jobs (
  id INTEGER NOT NULL AUTO_INCREMENT PRIMARY KEY,
  uuid BINARY(16) NOT NULL,
  display_id VARCHAR(100) NOT NULL,
  build_id INTEGER NOT NULL,
  details MEDIUMTEXT,
  start_time TIMESTAMP,
  end_time TIMESTAMP,
  status VARCHAR(30),
  FOREIGN KEY (build_id) REFERENCES builds(id)
);
--;;
CREATE INDEX job_uuid_idx ON jobs (uuid);
--;;
CREATE INDEX job_build_id_idx ON jobs (build_id);
--;;
CREATE INDEX job_display_id_idx ON jobs (display_id);
