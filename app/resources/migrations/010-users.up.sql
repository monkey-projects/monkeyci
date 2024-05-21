CREATE TABLE users (
  id INTEGER NOT NULL AUTO_INCREMENT PRIMARY KEY,
  cuid CHAR(24) NOT NULL,
  type VARCHAR(20) NOT NULL,
  type_id VARCHAR(100) NOT NULL,
  email VARCHAR(100)
);
--;;
CREATE INDEX user_cuid_idx ON users (cuid);
--;;
CREATE INDEX user_type_idx ON users (type, type_id);
