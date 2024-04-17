CREATE TABLE customer_params (
  id INTEGER NOT NULL AUTO_INCREMENT PRIMARY KEY,
  uuid BINARY(16) NOT NULL,
  customer_id INTEGER NOT NULL,
  name VARCHAR(100),
  value VARCHAR(1000),
  FOREIGN KEY (customer_id) REFERENCES customers(id)
);
