package org.tornotron.echno_backend.task;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TaskRepository extends JpaRepository<Task,Long> {
    Task findTaskByTitle(@NotBlank(message = "title is required") @Size(min = 3,max = 50,message = "title must be between 3 and 50 characters") String title);

//    List<Task> findTaskByEmployee_IdAndProject_Id(@NotNull(message = "employeeId is required") Long employeeId, @NotNull(message = "projectId is required") Long projectId);
}
