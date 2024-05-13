CREATE TABLE param_labels (
  id INTEGER NOT NULL AUTO_INCREMENT PRIMARY KEY,
  param_id INTEGER NOT NULL,
  name VARCHAR(100),
  value VARCHAR(100),
  FOREIGN KEY (param_id) REFERENCES customer_params(id)
);