-- Platform roles are separate from company roles. SYSTEM_ADMIN is never granted through the API;
-- it is set only through the restricted database procedure in docs/decisions.md.
ALTER TABLE users
    ADD CONSTRAINT ck_users_platform_role
        CHECK (platform_role IN ('USER', 'SYSTEM_ADMIN'));
