CREATE TABLE builds (
  id INTEGER NOT NULL AUTO_INCREMENT PRIMARY KEY,
  cuid CHAR(24) NOT NULL,
  idx INTEGER NOT NULL,
  repo_id INTEGER NOT NULL,
  start_time TIMESTAMP,
  end_time TIMESTAMP,
  status VARCHAR(30),
  FOREIGN KEY (repo_id) REFERENCES repos(id) ON DELETE CASCADE
);
--;;
CREATE INDEX build_cuid_idx ON builds (cuid);
--;;
CREATE INDEX build_repo_id_idx ON builds (repo_id);
