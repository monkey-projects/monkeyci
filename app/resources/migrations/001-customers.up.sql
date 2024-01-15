CREATE TABLE customers (
  id INTEGER GENERATED BY DEFAULT AS IDENTITY(START WITH 1) PRIMARY KEY,
  uuid UUID NOT NULL,
  name VARCHAR(200)
);
