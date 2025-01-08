package org.tornotron.echno_backend.task;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TaskRepository extends JpaRepository<Task,Long> {
    Task findTaskByTaskName(@NotBlank(message = "taskName is required") @Size(min = 3,max = 50,message = "taskName must be between 3 and 50 characters") String taskName);
}
