package io.github.gumwoo.fulfillment.infra.query;

import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.types.Projections;
import com.querydsl.jpa.impl.JPAQueryFactory;
import io.github.gumwoo.fulfillment.infra.persistence.AdminActionEntity;
import io.github.gumwoo.fulfillment.infra.persistence.QAdminActionEntity;
import io.github.gumwoo.fulfillment.infra.persistence.QConflictEntity;
import io.github.gumwoo.fulfillment.infra.persistence.QOrderLineStateEntity;
import io.github.gumwoo.fulfillment.infra.persistence.QRecallTaskEntity;
import io.github.gumwoo.fulfillment.infra.persistence.QUnlinkedEntity;
import java.time.LocalDateTime;
import java.util.List;

/** CS 어드민 목록 조회 (QueryDSL 동적 조건). 상태: OPEN(미해결) / RESOLVED(해결) / null(전체) */
public class AdminQueryRepository {

    public record UnlinkedRow(Long id, Long orderProductOptionNo, String orderNo, String productName, String optionName,
                              String optionValue, String reasonCode, String detail, LocalDateTime detectedAt,
                              LocalDateTime resolvedAt, String resolver) {
    }

    public record ConflictRow(Long id, String targetKey, String reasonCode, String detail, LocalDateTime detectedAt,
                              LocalDateTime resolvedAt) {
    }

    public record RecallRow(Long id, Long orderProductOptionNo, Long claimNo, String invoiceNo, String reason,
                            LocalDateTime createdAt, LocalDateTime resolvedAt) {
    }

    private final JPAQueryFactory query;

    public AdminQueryRepository(JPAQueryFactory query) {
        this.query = query;
    }

    public List<UnlinkedRow> unlinked(String reasonCode, String status, int limit) {
        QUnlinkedEntity u = QUnlinkedEntity.unlinkedEntity;
        QOrderLineStateEntity l = QOrderLineStateEntity.orderLineStateEntity;
        BooleanBuilder where = new BooleanBuilder();
        if (reasonCode != null && !reasonCode.isBlank()) {
            where.and(u.reasonCode.eq(reasonCode));
        }
        if ("OPEN".equals(status)) {
            where.and(u.resolvedAt.isNull());
        } else if ("RESOLVED".equals(status)) {
            where.and(u.resolvedAt.isNotNull());
        }
        return query.select(Projections.constructor(UnlinkedRow.class, u.id, u.orderProductOptionNo, l.orderNo,
                        l.productName, l.optionName, l.optionValue, u.reasonCode, u.detail, u.detectedAt, u.resolvedAt,
                        u.resolver))
                .from(u)
                .leftJoin(l).on(l.orderProductOptionNo.eq(u.orderProductOptionNo))
                .where(where)
                .orderBy(u.id.desc())
                .limit(limit)
                .fetch();
    }

    public List<ConflictRow> conflicts(String reasonCode, String status, int limit) {
        QConflictEntity c = QConflictEntity.conflictEntity;
        BooleanBuilder where = new BooleanBuilder();
        if (reasonCode != null && !reasonCode.isBlank()) {
            where.and(c.reasonCode.eq(reasonCode));
        }
        if ("OPEN".equals(status)) {
            where.and(c.resolvedAt.isNull());
        } else if ("RESOLVED".equals(status)) {
            where.and(c.resolvedAt.isNotNull());
        }
        return query.select(Projections.constructor(ConflictRow.class, c.id, c.targetKey, c.reasonCode, c.detail,
                        c.detectedAt, c.resolvedAt))
                .from(c).where(where).orderBy(c.id.desc()).limit(limit).fetch();
    }

    public List<RecallRow> recalls(String status, int limit) {
        QRecallTaskEntity r = QRecallTaskEntity.recallTaskEntity;
        BooleanBuilder where = new BooleanBuilder();
        if ("OPEN".equals(status)) {
            where.and(r.resolvedAt.isNull());
        } else if ("RESOLVED".equals(status)) {
            where.and(r.resolvedAt.isNotNull());
        }
        return query.select(Projections.constructor(RecallRow.class, r.id, r.orderProductOptionNo, r.claimNo,
                        r.invoiceNo, r.reason, r.createdAt, r.resolvedAt))
                .from(r).where(where).orderBy(r.id.desc()).limit(limit).fetch();
    }

    public List<AdminActionEntity> history(String targetKey, int limit) {
        QAdminActionEntity a = QAdminActionEntity.adminActionEntity;
        BooleanBuilder where = new BooleanBuilder();
        if (targetKey != null && !targetKey.isBlank()) {
            where.and(a.targetKey.eq(targetKey));
        }
        return query.selectFrom(a).where(where).orderBy(a.id.desc()).limit(limit).fetch();
    }
}
