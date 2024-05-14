CREATE TABLE customer_params (
  id INTEGER NOT NULL AUTO_INCREMENT PRIMARY KEY,
  uuid BINARY(16) NOT NULL,
  customer_id INTEGER NOT NULL,
  `name` VARCHAR(100) NOT NULL,
  `value` VARCHAR(1000) NOT NULL,
  description VARCHAR(300),
  FOREIGN KEY (customer_id) REFERENCES customers(id)
);
--;;
CREATE INDEX params_uuid_idx ON customer_params (uuid);
