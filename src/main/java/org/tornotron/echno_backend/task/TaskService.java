package org.tornotron.echno_backend.task;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.tornotron.echno_backend.task.dto.TaskCreationDto;
import org.tornotron.echno_backend.task.dto.TaskDto;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class TaskService {
    private final TaskRepository taskRepository;

    public TaskService(TaskRepository taskRepository) {
        this.taskRepository = taskRepository;
    }
    private TaskDto convertToDto(Task task) {
        TaskDto taskDto = new TaskDto();
        taskDto.setTaskName(task.getTaskName());
        taskDto.setCategories(task.getCategories());
        taskDto.setProgress(task.getProgress());
        return taskDto;
    }

    public Boolean addTask(TaskCreationDto taskCreationDto, MultipartFile photo) {
        if(taskCreationDto == null) {
            return false;
        }
        Task task = new Task();
        task.setTaskName(taskCreationDto.getTaskName());
        task.setCategories(taskCreationDto.getCategories());
        task.setProgress(taskCreationDto.getProgress());
        try {
        if(photo != null && !photo.isEmpty()) {
            //write validation for file size
            task.setPhoto(photo.getBytes());
        }
            taskRepository.save(task);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public List<TaskDto> getAllTasks() {
        return taskRepository.findAll().stream()
                .map(this::convertToDto)
                .collect(Collectors.toList());
    }

    public TaskDto getATask(Long id) {
        return taskRepository.findById(id)
                .map(this::convertToDto)
                .orElse(null);
    }

    public Boolean updateATask(Task updatedTask,Long id) {
        if(updatedTask == null) {
            return null;
        }
        Optional<Task> optionalTask = taskRepository.findById(id);
        if(optionalTask.isPresent()) {
            Task taskObj = optionalTask.get();
            taskObj.setTaskName(updatedTask.getTaskName());
            taskObj.setCategories(updatedTask.getCategories());
            taskObj.setProgress(updatedTask.getProgress());
            taskRepository.save(taskObj);
            return true;
        }
        return false;
    }

    public Boolean deleteATask(Long id) {
        try {
            taskRepository.deleteById(id);
            return true;
        } catch (Exception e) {
            return false;
        }
    }


}
