package org.tornotron.echno_backend.pdfGeneration;

import org.springframework.stereotype.Service;
import org.tornotron.echno_backend.task.TaskService;

import java.util.Map;
import java.util.stream.Collectors;


@Service
public class ReportService {

    private final TaskService taskService;

    public ReportService(TaskService taskService) {
        this.taskService = taskService;
    }

    public Map<String, Long> statusCount() {
        return taskService.getAllTasks().stream()
                .collect(Collectors.groupingBy(t -> t.getStatus() == null ? "Unknown" : t.getStatus().toString(), Collectors.counting()));
    }


}
