CREATE TABLE webhooks (
  id INTEGER NOT NULL AUTO_INCREMENT PRIMARY KEY,
  uuid BINARY(16) NOT NULL,
  repo_id INTEGER NOT NULL,
  secret VARCHAR(50) NOT NULL,
  FOREIGN KEY (repo_id) REFERENCES repos(id) ON DELETE CASCADE
);
--;;
CREATE INDEX webhooks_repo_id_idx ON webhooks (repo_id);
