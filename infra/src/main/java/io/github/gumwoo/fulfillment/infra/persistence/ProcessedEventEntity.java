package io.github.gumwoo.fulfillment.infra.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/** 이미 반영한 변경의 멱등 키 (ADR-0001). PK가 곧 UNIQUE 제약이다. */
@Entity
@Table(name = "processed_event")
public class ProcessedEventEntity {

    @Id
    @Column(length = 64)
    private String idemKey;

    @Column(nullable = false, length = 32)
    private String source;

    @Column(nullable = false)
    private LocalDateTime processedAt;

    protected ProcessedEventEntity() {
    }

    public String getIdemKey() {
        return idemKey;
    }

    public String getSource() {
        return source;
    }
}
