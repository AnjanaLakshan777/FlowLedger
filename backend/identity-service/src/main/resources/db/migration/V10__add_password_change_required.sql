-- An account created by a company admin starts with the admin's temporary password. Until the
-- user replaces it, sign-in issues no access token. Existing accounts are not affected.
ALTER TABLE users
    ADD COLUMN password_change_required BOOLEAN NOT NULL DEFAULT false;
