CREATE TABLE users (
  id INTEGER NOT NULL AUTO_INCREMENT PRIMARY KEY,
  uuid BINARY(16) NOT NULL,
  type VARCHAR(20) NOT NULL,
  type_id VARCHAR(100) NOT NULL,
  email VARCHAR(100)
);
--;;
CREATE INDEX user_uuid_idx ON users (uuid);
--;;
CREATE INDEX user_type_idx ON users (type, type_id);
