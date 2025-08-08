ALTER TABLE Organization
    ADD COLUMN organization_email VARCHAR(255) NOT NULL,
    ADD COLUMN organization_phone VARCHAR(50) NOT NULL,
    ADD COLUMN organization_website VARCHAR(500),
    ADD COLUMN organization_logo VARCHAR(500),
    ADD COLUMN creator_id INTEGER,
    ADD COLUMN is_active BOOLEAN DEFAULT true;

ALTER TABLE Employee
    ADD COLUMN organization_id BIGINT REFERENCES Organization(id)