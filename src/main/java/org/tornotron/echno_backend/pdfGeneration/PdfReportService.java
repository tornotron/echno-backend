package org.tornotron.echno_backend.pdfGeneration;

import org.springframework.stereotype.Service;
import org.tornotron.echno_backend.task.TaskService;

import java.util.Map;


@Service
public class PdfReportService {

    private final TaskService taskService;

    public PdfReportService(TaskService taskService) {
        this.taskService = taskService;
    }

    /**
     * The task status breakdown shown on the PDF report.
     *
     * <p>Delegates to a database aggregate rather than loading every task and grouping in memory,
     * so the figure stays correct and the cost stays flat however much history a tenant has.
     *
     * @return Status name to task count.
     */
    public Map<String, Long> statusCount() {
        return taskService.countTasksByStatus();
    }


}
