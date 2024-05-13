CREATE TABLE customers (
  id INTEGER NOT NULL AUTO_INCREMENT PRIMARY KEY,
  uuid BINARY(16) NOT NULL,
  `name` VARCHAR(200) NOT NULL,
  INDEX customer_uuid_idx (uuid)
);