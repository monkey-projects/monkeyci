CREATE TABLE builds (
  id INTEGER NOT NULL AUTO_INCREMENT PRIMARY KEY,
  uuid BINARY(16) NOT NULL,
  repo_id INTEGER NOT NULL,
  jobs MEDIUMTEXT,
  FOREIGN KEY (repo_id) REFERENCES repos(id)
);
