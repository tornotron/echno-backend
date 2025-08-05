-- Create Organization Table
CREATE TABLE Organization (
    id BIGSERIAL PRIMARY KEY,
    organization_name VARCHAR(255) UNIQUE,
    organization_address VARCHAR(255),
    created_at TIMESTAMP
);

CREATE INDEX idx_organization_name ON Organization (organization_name);

-- Create Project Table
CREATE TABLE Project (
    id BIGSERIAL PRIMARY KEY,
    project_name VARCHAR(255) UNIQUE,
    project_address VARCHAR(255),
    created_at TIMESTAMP,
    status VARCHAR(255),
    organization_id BIGINT REFERENCES Organization(id)
);

CREATE INDEX idx_project_name ON Project (project_name);

-- Create TeamMember Table
CREATE TABLE Team_member (
    id BIGSERIAL PRIMARY KEY,
    member_name VARCHAR(255) UNIQUE,
    member_email VARCHAR(255) UNIQUE,
    member_phone VARCHAR(255) UNIQUE,
    member_role VARCHAR(255),
    member_image VARCHAR(255),
    project_id BIGINT REFERENCES Project(id)
);

-- Create Employee Table
CREATE TABLE Employee (
    id BIGSERIAL PRIMARY KEY,
    employee_name VARCHAR(255) NOT NULL,
    gender VARCHAR(255) NOT NULL,
    phone_number VARCHAR(255) NOT NULL,
    email_address VARCHAR(255) NOT NULL UNIQUE,
    date_of_birth TIMESTAMP NOT NULL
);

-- Create Category Table
CREATE TABLE Category (
    id BIGSERIAL PRIMARY KEY,
    category_name VARCHAR(255) UNIQUE NOT NULL,
    category_description VARCHAR(255),
    category_icon VARCHAR(255),
    category_image VARCHAR(255)
);

-- Create Task Table
CREATE TABLE Task (
    id BIGSERIAL PRIMARY KEY,
    title VARCHAR(255) NOT NULL,
    start_date TIMESTAMP,
    end_date TIMESTAMP,
    creator_id BIGINT NOT NULL REFERENCES Employee(id),
    project_id BIGINT NOT NULL REFERENCES Project(id),
    category_id BIGINT REFERENCES Category(id),
    progress DOUBLE PRECISION,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    status VARCHAR(255) NOT NULL
);

-- Create Task Assignees Join Table
CREATE TABLE task_assignees (
    task_id BIGINT NOT NULL REFERENCES Task(id),
    creator_id BIGINT NOT NULL REFERENCES Employee(id),
    PRIMARY KEY (task_id, creator_id)
);

-- Create Task Tags Table
CREATE TABLE task_tags (
    id BIGINT NOT NULL REFERENCES Task(id),
    tags VARCHAR(255)
);

-- Create Attendance Table
CREATE TABLE Attendance (
    id BIGSERIAL PRIMARY KEY,
    employee_id BIGINT NOT NULL,
    location VARCHAR(255) NOT NULL,
    record_type VARCHAR(255),
    "timestamp" TIMESTAMP NOT NULL,
    source VARCHAR(255),
    latitude DOUBLE PRECISION,
    longitude DOUBLE PRECISION,
    device_info JSONB
);

-- Create Issue Table
CREATE TABLE Issue (
    id BIGSERIAL PRIMARY KEY,
    title VARCHAR(255) NOT NULL UNIQUE,
    description VARCHAR(255),
    type VARCHAR(255) NOT NULL,
    status VARCHAR(255) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    creator VARCHAR(255)
);

-- Create ProjectInviteCode Table
CREATE TABLE ProjectInviteCode (
    id BIGSERIAL PRIMARY KEY,
    code INT NOT NULL UNIQUE,
    project_id BIGINT REFERENCES Project(id),
    expiry_date TIMESTAMP NOT NULL,
    is_active BOOLEAN NOT NULL,
    max_uses INT NOT NULL,
    current_uses INT NOT NULL
);
