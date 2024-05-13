CREATE TABLE ssh_key_labels (
  id INTEGER NOT NULL AUTO_INCREMENT PRIMARY KEY,
  ssh_key_id INTEGER NOT NULL,
  name VARCHAR(100),
  value VARCHAR(100),
  FOREIGN KEY (ssh_key_id) REFERENCES ssh_keys(id)
);
