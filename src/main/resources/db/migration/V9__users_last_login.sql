ALTER TABLE users ADD COLUMN last_login_at timestamptz NULL;
CREATE INDEX idx_users_last_login_at ON users (last_login_at);
