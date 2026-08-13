CREATE TABLE categories (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(50) NOT NULL UNIQUE,
    type VARCHAR(10) NOT NULL
);

INSERT INTO categories (name, type) VALUES
('Food & Groceries', 'EXPENSE'),
('Transport', 'EXPENSE'),
('Entertainment', 'EXPENSE'),
('Utilities', 'EXPENSE'),
('Salary', 'INCOME'),
('Investments', 'INCOME'),
('Other', 'EXPENSE');

ALTER TABLE transactions ADD COLUMN category_id BIGINT REFERENCES categories(id);