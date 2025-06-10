CREATE TABLE Organization (
                              id BIGSERIAL PRIMARY KEY,
                              organization_name VARCHAR(255) UNIQUE,
                              organization_address VARCHAR(255),
                              created_at TIMESTAMP
);

CREATE INDEX idx_organization_name ON Organization(organization_name);

CREATE TABLE Project (
                         id BIGSERIAL PRIMARY KEY,
                         project_name VARCHAR(255) UNIQUE,
                         project_address VARCHAR(255),
                         created_at TIMESTAMP,
                         status VARCHAR(50),
                         organization_id BIGINT,
                         FOREIGN KEY (organization_id) REFERENCES Organization(id)
);

CREATE INDEX idx_project_name ON Project(project_name);

CREATE TABLE Employee (
                          id BIGSERIAL PRIMARY KEY,
                          employee_name VARCHAR(255) NOT NULL,
                          gender VARCHAR(50) NOT NULL,
                          phone_number VARCHAR(20) NOT NULL,
                          email_address VARCHAR(255) NOT NULL,
                          date_of_birth TIMESTAMP NOT NULL
);

CREATE TABLE Task (
                      id BIGSERIAL PRIMARY KEY,
                      taskName VARCHAR(50) UNIQUE NOT NULL,
                      categories VARCHAR(50) NOT NULL,
                      photo BYTEA,
                      progress INTEGER
);

CREATE TABLE Team_member (
                             id BIGSERIAL PRIMARY KEY,
                             member_name VARCHAR(255) UNIQUE,
                             member_email VARCHAR(255) UNIQUE,
                             member_phone VARCHAR(255) UNIQUE,
                             member_role VARCHAR(255),
                             member_image VARCHAR(255),
                             project_id BIGINT,
                             FOREIGN KEY (project_id) REFERENCES Project(id)
);

CREATE TABLE Attendance (
                            id BIGSERIAL PRIMARY KEY,
                            employeeId BIGINT NOT NULL,
                            location VARCHAR(255) NOT NULL,
                            recordType VARCHAR(50),
                            timestamp TIMESTAMP NOT NULL,
                            source VARCHAR(255),
                            geo_latitude DOUBLE PRECISION,
                            geo_longitude DOUBLE PRECISION,
                            deviceInfo JSONB,
                            FOREIGN KEY (employeeId) REFERENCES Employee(id)
);

CREATE TABLE ProjectInviteCode (
                                   id BIGSERIAL PRIMARY KEY,
                                   code INTEGER UNIQUE NOT NULL,
                                   project_id BIGINT NOT NULL,
                                   expiryDate TIMESTAMP NOT NULL,
                                   isActive BOOLEAN NOT NULL,
                                   maxUses INTEGER NOT NULL,
                                   currentUses INTEGER NOT NULL,
                                   FOREIGN KEY (project_id) REFERENCES Project(id)
);