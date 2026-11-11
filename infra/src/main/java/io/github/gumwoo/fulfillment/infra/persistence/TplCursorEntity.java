package io.github.gumwoo.fulfillment.infra.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 3PL 이벤트를 어디까지 가져왔는지 (이벤트 순번) */
@Entity
@Table(name = "tpl_cursor")
public class TplCursorEntity {

    @Id
    @Column(length = 32)
    private String name;

    private long lastSeq;

    protected TplCursorEntity() {
    }

    public TplCursorEntity(String name, long lastSeq) {
        this.name = name;
        this.lastSeq = lastSeq;
    }

    public long getLastSeq() {
        return lastSeq;
    }

    public void setLastSeq(long lastSeq) {
        this.lastSeq = lastSeq;
    }
}
