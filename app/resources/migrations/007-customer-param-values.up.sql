CREATE TABLE customer_param_values (
  id INTEGER NOT NULL AUTO_INCREMENT PRIMARY KEY,
  params_id INTEGER NOT NULL,
  `name` VARCHAR(100) NOT NULL,
  `value` MEDIUMTEXT NOT NULL,
  FOREIGN KEY (params_id) REFERENCES customer_params(id) ON DELETE CASCADE
);
--;;
CREATE INDEX customer_params_values_params_id_idx ON customer_param_values (params_id);
