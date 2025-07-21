-- Create Category Table
CREATE TABLE Category (
    id BIGSERIAL PRIMARY KEY,
    category_name VARCHAR(255) UNIQUE NOT NULL,
    category_description VARCHAR(255),
    category_icon VARCHAR(255),
    category_image VARCHAR(255)
);

-- Drop the old Task table
DROP TABLE Task;

-- Create the new Task table
CREATE TABLE Task (
                      id BIGSERIAL PRIMARY KEY,
                      title VARCHAR(255) NOT NULL,
                      start_date TIMESTAMP,
                      end_date TIMESTAMP,
                      progress DOUBLE PRECISION,
                      created_at TIMESTAMP,
                      updated_at TIMESTAMP,
                      status VARCHAR(255) NOT NULL,
                      creator_id BIGINT NOT NULL,
                      project_id BIGINT NOT NULL,
                      category_id BIGINT,
                      FOREIGN KEY (creator_id) REFERENCES Employee(id),
                      FOREIGN KEY (project_id) REFERENCES Project(id),
                      FOREIGN KEY (category_id) REFERENCES Category(id)
);

-- Create task_assignees join table
CREATE TABLE task_assignees (
    task_id BIGINT NOT NULL,
    employee_id BIGINT NOT NULL,
    PRIMARY KEY (task_id, employee_id),
    FOREIGN KEY (task_id) REFERENCES Task(id),
    FOREIGN KEY (employee_id) REFERENCES Employee(id)
);

-- Create task_tags table
CREATE TABLE task_tags (
    id BIGINT NOT NULL,
    tags VARCHAR(255),
    FOREIGN KEY (id) REFERENCES Task(id)
);

-- Update Employee table
ALTER TABLE Employee
    ALTER COLUMN gender TYPE VARCHAR(255),
    ALTER COLUMN phone_number TYPE VARCHAR(255),
    ADD CONSTRAINT uk_employee_email_address UNIQUE (email_address);

-- Update Project table
ALTER TABLE Project
    ALTER COLUMN status TYPE VARCHAR(255);
