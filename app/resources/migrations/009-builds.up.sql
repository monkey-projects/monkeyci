CREATE TABLE builds (
  id INTEGER NOT NULL AUTO_INCREMENT PRIMARY KEY,
  uuid BINARY(16) NOT NULL,
  idx INTEGER NOT NULL,
  repo_id INTEGER NOT NULL,
  FOREIGN KEY (repo_id) REFERENCES repos(id)
);
--;;
CREATE INDEX build_uuid_idx ON builds (uuid);
--;;
CREATE INDEX build_repo_id_idx ON builds (repo_id);
