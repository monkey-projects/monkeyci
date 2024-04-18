CREATE TABLE repos (
  id INTEGER NOT NULL AUTO_INCREMENT PRIMARY KEY,
  uuid BINARY(16) NOT NULL,
  customer_id INTEGER NOT NULL,
  name VARCHAR(200) NOT NULL,
  url VARCHAR(300),
  main_branch VARCHAR(100),
  INDEX repo_uuid_idx (uuid),
  FOREIGN KEY (customer_id) REFERENCES customers(id)
);
