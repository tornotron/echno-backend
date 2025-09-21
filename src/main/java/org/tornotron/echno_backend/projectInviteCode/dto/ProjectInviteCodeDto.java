package org.tornotron.echno_backend.projectInviteCode.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

@Data
public class ProjectInviteCodeDto {
    private Long id;
    private int code;
    private LocalDateTime expiryDate;
    private boolean isActive;
    private int maxUses;
    private int currentUses;
    private Map<String , Object> employeeDetails;
}
