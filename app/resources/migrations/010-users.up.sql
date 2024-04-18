CREATE TABLE users (
  id INTEGER NOT NULL AUTO_INCREMENT PRIMARY KEY,
  uuid BINARY(16) NOT NULL,
  type VARCHAR(20) NOT NULL,
  type_id VARCHAR(100) NOT NULL,
  email VARCHAR(100),
  INDEX user_uuid_idx (uuid),
  INDEX user_type_idx (type, type_id)
);
