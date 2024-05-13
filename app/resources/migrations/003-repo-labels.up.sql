-- Labels assigned to repositories.
-- These are used to link parameters and ssh keys.
CREATE TABLE repo_labels (
  id INTEGER NOT NULL AUTO_INCREMENT PRIMARY KEY,
  repo_id INTEGER NOT NULL,
  name VARCHAR(100),
  value VARCHAR(100),
  FOREIGN KEY (repo_id) REFERENCES repos(id)
);
