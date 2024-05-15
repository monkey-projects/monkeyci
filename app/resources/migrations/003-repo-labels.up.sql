CREATE TABLE repo_labels (
  id INTEGER NOT NULL AUTO_INCREMENT PRIMARY KEY,
  repo_id INTEGER NOT NULL,
  `name` VARCHAR(100),
  `value` VARCHAR(100),
  FOREIGN KEY (repo_id) REFERENCES repos(id)
);
--;;
CREATE INDEX repo_labels_repo_id_idx ON repo_labels (repo_id);
