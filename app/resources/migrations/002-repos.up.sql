CREATE TABLE repos (
  id INTEGER GENERATED BY DEFAULT AS IDENTITY(START WITH 1) PRIMARY KEY,
  customer_id INTEGER NOT NULL,
  uuid UUID NOT NULL,
  name VARCHAR(200),
  url VARCHAR(300),
  FOREIGN KEY (customer_id) REFERENCES customers(id)
);
