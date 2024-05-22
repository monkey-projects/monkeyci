CREATE TABLE customer_params (
  id INTEGER NOT NULL AUTO_INCREMENT PRIMARY KEY,
  cuid CHAR(24) NOT NULL,
  customer_id INTEGER NOT NULL,
  description VARCHAR(300),
  label_filters TEXT,
  FOREIGN KEY (customer_id) REFERENCES customers(id) ON DELETE CASCADE
);
--;;
CREATE INDEX params_cuid_idx ON customer_params (cuid);
--;;
CREATE INDEX customer_params_customer_id_idx ON customer_params (customer_id);
