CREATE TABLE join_requests (
  id INTEGER NOT NULL AUTO_INCREMENT PRIMARY KEY,
  cuid CHAR(24) NOT NULL,
  customer_id INTEGER NOT NULL,
  user_id INTEGER NOT NULL,
  status VARCHAR(30) NOT NULL,
  request_msg VARCHAR(500),
  response_msg VARCHAR(500),
  FOREIGN KEY (customer_id) REFERENCES customers(id) ON DELETE CASCADE,
  FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);
--;;
CREATE UNIQUE INDEX join_request_cuid_idx ON join_requests(cuid);
--;;
CREATE INDEX join_request_customer_id_idx ON join_requests (customer_id);
--;;
CREATE INDEX join_request_user_id_idx ON join_requests (user_id);
