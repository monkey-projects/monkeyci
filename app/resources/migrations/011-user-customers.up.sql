CREATE TABLE user_customers (
  user_id INTEGER NOT NULL,
  customer_id INTEGER NOT NULL,
  PRIMARY KEY (user_id, customer_id),
  FOREIGN KEY (user_id) REFERENCES users(id),
  FOREIGN KEY (customer_id) REFERENCES customers(id)
);
