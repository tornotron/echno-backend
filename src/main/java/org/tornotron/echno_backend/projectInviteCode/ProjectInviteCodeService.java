package org.tornotron.echno_backend.projectInviteCode;

import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.project.ProjectRepository;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Optional;

@Service
@Transactional
public class ProjectInviteCodeService {

    private final ProjectInviteCodeRepository inviteCodeRepository;
    private final ProjectRepository projectRepository;
    private final SecureRandom secureRandom = new SecureRandom();

    public ProjectInviteCodeService(ProjectInviteCodeRepository inviteCodeRepository, ProjectRepository projectRepository) {
        this.inviteCodeRepository = inviteCodeRepository;
        this.projectRepository = projectRepository;
    }

    public int generateSecureFiveDigitNumber() {
        return 10000 + secureRandom.nextInt(90000);
    }

    public Boolean generateInviteCode(String projectName, int maxUses, int validityDays) {
        Project project = projectRepository.findProjectByProjectName(projectName);
        if(project == null) {
            return null;
        }
        int inviteCode = generateSecureFiveDigitNumber();
        ProjectInviteCode projectInviteCode = new ProjectInviteCode();
        projectInviteCode.setCode(inviteCode);
        projectInviteCode.setProject(project);
        projectInviteCode.setExpiryDate(LocalDateTime.now().plusDays(validityDays));
        projectInviteCode.setActive(true);
        projectInviteCode.setMaxUses(maxUses);
        projectInviteCode.setCurrentUses(0);
        try {
            inviteCodeRepository.save(projectInviteCode);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public Boolean validateAndUseInviteCode(int code) {
        Optional<ProjectInviteCode> inviteCodeOptional = inviteCodeRepository.findByCode(code);
        if(inviteCodeOptional.isPresent()) {
            ProjectInviteCode projectInviteCode = inviteCodeOptional.get();
            if(projectInviteCode.getExpiryDate().isBefore(LocalDateTime.now())) {
//                throw new
                return false;
            }
            if(!projectInviteCode.isActive()) {
//                throw new
                return false;
            }
            if(projectInviteCode.getCurrentUses() >= projectInviteCode.getMaxUses()) {
//                throw new
                return false;
            }
            projectInviteCode.setCurrentUses(projectInviteCode.getCurrentUses() + 1);

            if(projectInviteCode.getCurrentUses() >= projectInviteCode.getMaxUses()) {
                projectInviteCode.setActive(false);
            }
            try {
                inviteCodeRepository.save(projectInviteCode);
                return true;
            } catch (Exception e) {
                return false;
            }
        } else {
            return null;
        }

    }
}
