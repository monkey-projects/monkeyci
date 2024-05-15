CREATE TABLE user_customers (
  user_id INTEGER NOT NULL,
  customer_id INTEGER NOT NULL,
  PRIMARY KEY (user_id, customer_id),
  FOREIGN KEY (user_id) REFERENCES users(id),
  FOREIGN KEY (customer_id) REFERENCES customers(id)
);
--;;
CREATE INDEX user_customers_user_id_idx ON user_customers (user_id);
--;;
CREATE INDEX user_customers_customer_id_idx ON user_customers (customer_id);
