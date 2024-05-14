CREATE TABLE ssh_keys (
  id INTEGER NOT NULL AUTO_INCREMENT PRIMARY KEY,
  uuid BINARY(16) NOT NULL,
  customer_id INTEGER NOT NULL,
  description VARCHAR(300) NOT NULL,
  private_key VARCHAR(500) NOT NULL,
  public_key VARCHAR(500) NOT NULL,
  FOREIGN KEY (customer_id) REFERENCES customers(id)
);
--;;
CREATE INDEX ssh_keys_uuid_idx ON ssh_keys (uuid);
