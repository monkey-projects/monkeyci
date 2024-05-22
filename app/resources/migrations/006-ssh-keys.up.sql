CREATE TABLE ssh_keys (
  id INTEGER NOT NULL AUTO_INCREMENT PRIMARY KEY,
  cuid CHAR(24) NOT NULL,
  customer_id INTEGER NOT NULL,
  description VARCHAR(300) NOT NULL,
  private_key TEXT NOT NULL,
  public_key TEXT NOT NULL,
  label_filters TEXT,
  FOREIGN KEY (customer_id) REFERENCES customers(id) ON DELETE CASCADE
);
--;;
CREATE INDEX ssh_keys_cuid_idx ON ssh_keys (cuid);
--;;
CREATE INDEX ssh_keys_customer_id_idx ON ssh_keys (customer_id);
