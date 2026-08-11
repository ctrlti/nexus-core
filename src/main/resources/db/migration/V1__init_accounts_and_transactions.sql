CREATE TABLE accounts (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(50) NOT NULL UNIQUE,
    currency VARCHAR(10) NOT NULL,
    type VARCHAR(20) NOT NULL,
    balance NUMERIC(12, 2) NOT NULL DEFAULT 0.00,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE transactions (
    id BIGSERIAL PRIMARY KEY,
    account_id BIGINT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    amount NUMERIC(12, 2) NOT NULL,
    type VARCHAR(10) NOT NULL,
    comment VARCHAR(255),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- Insert initial 4 accounts
INSERT INTO accounts (name, currency, type, balance) VALUES
('USD Cash', 'USD', 'CASH', 0.00),
('USD Card', 'USD', 'CARD', 0.00),
('EUR Cash', 'EUR', 'CASH', 0.00),
('EUR Card', 'EUR', 'CARD', 0.00);