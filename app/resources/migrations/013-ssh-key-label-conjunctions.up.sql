CREATE TABLE ssh_key_label_conjunctions (
  id INTEGER NOT NULL AUTO_INCREMENT PRIMARY KEY,
  label_id INTEGER NOT NULL,
  `name` VARCHAR(100),
  `value` VARCHAR(100),
  FOREIGN KEY (label_id) REFERENCES ssh_key_labels(id) ON DELETE CASCADE
);
--;;
CREATE INDEX ssh_key_labels_label_id_idx ON ssh_key_label_conjunctions (label_id);
