CREATE TABLE repos (
  id INTEGER NOT NULL AUTO_INCREMENT PRIMARY KEY,
  cuid CHAR(24) NOT NULL,
  display_id VARCHAR(50) NOT NULL,
  customer_id INTEGER NOT NULL,
  name VARCHAR(200) NOT NULL,
  url VARCHAR(300),
  main_branch VARCHAR(100),
  github_id INTEGER,
  FOREIGN KEY (customer_id) REFERENCES customers(id) ON DELETE CASCADE
);
--;;
CREATE INDEX repo_cuid_idx ON repos (cuid);
--;;
CREATE INDEX repo_customer_id_idx ON repos (customer_id);
