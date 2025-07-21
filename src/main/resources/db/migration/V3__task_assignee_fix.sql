-- Step 1: Drop existing foreign key constraints on task_assignees
ALTER TABLE task_assignees DROP CONSTRAINT task_assignees_task_id_fkey;
ALTER TABLE task_assignees DROP CONSTRAINT task_assignees_employee_id_fkey;

-- Step 2: Drop the existing primary key
ALTER TABLE task_assignees DROP CONSTRAINT task_assignees_pkey;

-- Step 3: Rename the column from employee_id to creator_id
ALTER TABLE task_assignees RENAME COLUMN employee_id TO creator_id;

-- Step 4: Recreate the primary key with the new column name
ALTER TABLE task_assignees ADD PRIMARY KEY (task_id, creator_id);

-- Step 5: Recreate the foreign key constraints with the new column name
ALTER TABLE task_assignees ADD CONSTRAINT task_assignees_task_id_fkey FOREIGN KEY (task_id) REFERENCES Task(id);
ALTER TABLE task_assignees ADD CONSTRAINT task_assignees_creator_id_fkey FOREIGN KEY (creator_id) REFERENCES Employee(id);
