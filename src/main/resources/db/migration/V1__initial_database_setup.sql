-- Create Organization Table

CREATE TABLE Users_table (
                             id BIGSERIAL PRIMARY KEY,
                             name VARCHAR(255) NOT NULL,
                             blood_group VARCHAR(10),
                             email VARCHAR(255) NOT NULL UNIQUE,
                             phone VARCHAR(15) NOT NULL UNIQUE,
                             date_of_birth TIMESTAMP NOT NULL,
                             qualification VARCHAR(255),
--     skills
                             cv_url VARCHAR(255),
                             emergency_contact VARCHAR(15),
                             role VARCHAR(50) NOT NULL,
                             profile_picture_url VARCHAR(255),
                             created_at TIMESTAMP NOT NULL,
                             updated_at TIMESTAMP NOT NULL,
                             experience INTEGER,
                             skills text[],
                             gender VARCHAR(255) NOT NULL
);
CREATE TABLE Organization (
                              id BIGSERIAL PRIMARY KEY,
                              organization_name VARCHAR(255) UNIQUE,
                              organization_address VARCHAR(255),
                              organization_email VARCHAR(255) NOT NULL,
                              organization_phone VARCHAR(50) NOT NULL,
                              organization_website VARCHAR(500),
                              organization_logo VARCHAR(500),
                              creator_id INTEGER,
                              created_at TIMESTAMP,
                              is_active BOOLEAN DEFAULT true
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
                          designation VARCHAR(255) NOT NULL,
                          department VARCHAR(255) NOT NULL,
                          joining_date TIMESTAMP,
                          salary DOUBLE PRECISION,
                          reporting_manager VARCHAR(255),
                          shift_timing VARCHAR(255),
                          status VARCHAR(255) NOT NULL,
--     certification VARCHAR(255),
                          employee_name VARCHAR(255) NOT NULL,
                          gender VARCHAR(255) NOT NULL,
                          phone_number VARCHAR(255) NOT NULL,
                          email_address VARCHAR(255) NOT NULL UNIQUE,
                          date_of_birth TIMESTAMP NOT NULL,
                          organization_id BIGINT,
                          user_id BIGINT,
                          FOREIGN KEY (organization_id) REFERENCES Organization(id),
                          FOREIGN KEY (user_id) REFERENCES Users_table(id)

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


CREATE TABLE Issue_comments (
                                id BIGSERIAL PRIMARY KEY,
                                issue_id BIGINT REFERENCES Issue(id),
                                comment TEXT NOT NULL,
                                created_at TIMESTAMP NOT NULL
);
