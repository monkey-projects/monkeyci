CREATE TABLE customers (
  id INTEGER NOT NULL AUTO_INCREMENT PRIMARY KEY,
  cuid CHAR(24) NOT NULL,
  `name` VARCHAR(200) NOT NULL UNIQUE
);
--;;
CREATE UNIQUE INDEX customer_cuid_idx ON customers (cuid);
