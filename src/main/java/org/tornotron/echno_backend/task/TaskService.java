package org.tornotron.echno_backend.task;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.ValidationException;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.tornotron.echno_backend.common.exception.DatabaseOperationException;
import org.tornotron.echno_backend.common.exception.InvalidPhotoException;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.task.dto.TaskCreationDto;
import org.tornotron.echno_backend.task.dto.TaskDto;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class TaskService {
    private final TaskRepository taskRepository;
    private static final long MAX_FILE_SIZE=10*1024*1024;
    private static final String[] ALLOWED_IMAGE_TYPES = {"image/jpeg", "image/png", "image/jpg"};
    private final ObjectMapper objectMapper;

    public TaskService(TaskRepository taskRepository, ObjectMapper objectMapper) {
        this.taskRepository = taskRepository;
        this.objectMapper = objectMapper;
    }

    private void validatePhoto(MultipartFile photo) {
        if(photo.isEmpty()) {
            throw new InvalidPhotoException("Photo file is empty");
        }
        if(photo.getSize() > MAX_FILE_SIZE) {
            throw new InvalidPhotoException("File size exceeds maximum limit of 10MB");
        }
        String contentType = photo.getContentType();
        if(contentType == null || !Arrays.asList(ALLOWED_IMAGE_TYPES).contains(contentType)) {
            throw new InvalidPhotoException("Invalid file type. Allowed types: JPEG, PNG");
        }
    }

    private TaskCreationDto parseAndValidateTaskDto(String taskDtoString) {
        try {


            TaskCreationDto taskDto = objectMapper.readValue(taskDtoString, TaskCreationDto.class);

            Set<ConstraintViolation<TaskCreationDto>> violations = Validation.buildDefaultValidatorFactory()
                    .getValidator()
                    .validate(taskDto);
            if(!violations.isEmpty()) {
                Map<String,String> validationErrors = violations.stream()
                        .collect(Collectors.toMap(
                                violation -> violation.getPropertyPath().toString(),
                                ConstraintViolation::getMessage
                        ));
                throw new ValidationException("Invalid task data");
            }
            return taskDto;
        } catch (JsonProcessingException e) {
            throw new InvalidRequestException("Invalid Json format in task");
        }
    }

    private TaskDto convertToDto(Task task) {
        TaskDto taskDto = new TaskDto();
        taskDto.setTaskName(task.getTaskName());
        taskDto.setCategories(task.getCategories());
        taskDto.setProgress(task.getProgress());
        return taskDto;
    }

    public void addTask(String taskDtoString, MultipartFile photo) {
        TaskCreationDto taskCreationDto = parseAndValidateTaskDto(taskDtoString);
        Task task = new Task();
        task.setTaskName(taskCreationDto.getTaskName());
        task.setCategories(taskCreationDto.getCategories());
        task.setProgress(taskCreationDto.getProgress());
        try {
        if(photo != null) {
            validatePhoto(photo);
            task.setPhoto(photo.getBytes());
        }
            taskRepository.save(task);
        } catch (Exception e) {
            throw new DatabaseOperationException("Task could not be saved");
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
