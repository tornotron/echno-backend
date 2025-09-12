package org.tornotron.echno_backend.intend;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.tornotron.echno_backend.indentItem.IndentItem;
import org.tornotron.echno_backend.intend.enums.IntendStatus;
import org.tornotron.echno_backend.user.User;

import java.time.LocalDateTime;
import java.util.List;

@Entity
@NoArgsConstructor
@Data
public class Intend {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "intend_number", nullable = false, unique = true)
    private String intendNumber;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @ManyToOne
    private User createdBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private IntendStatus status;

    @Column(name = "expected_on")
    private LocalDateTime expectedOn;

    @Column(name = "remark")
    private String remarks;

    @OneToMany(mappedBy = "intend")
    private List<IndentItem> items;
}
