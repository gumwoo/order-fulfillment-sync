package io.github.gumwoo.fulfillment.infra.persistence;

import io.github.gumwoo.fulfillment.core.cancel.CancelState;
import io.github.gumwoo.fulfillment.core.port.CancelStore;
import io.github.gumwoo.fulfillment.core.port.ConflictRecorder;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;

public class JpaCancelStore implements CancelStore, ConflictRecorder {

    private final CancelCaseRepository cases;
    private final RecallTaskRepository recalls;
    private final ConflictRepository conflicts;

    public JpaCancelStore(CancelCaseRepository cases, RecallTaskRepository recalls, ConflictRepository conflicts) {
        this.cases = cases;
        this.recalls = recalls;
        this.conflicts = conflicts;
    }

    @Override
    public Optional<CancelCase> find(long optionNo, long claimNo) {
        return cases.findByOrderProductOptionNoAndClaimNo(optionNo, claimNo).map(CancelCaseEntity::toDomain);
    }

    @Override
    public void createIfAbsent(long optionNo, long claimNo, LocalDateTime at) {
        cases.insertIgnore(optionNo, claimNo, at);
    }

    @Override
    public Optional<CancelCase> lock(long optionNo, long claimNo) {
        return cases.lock(optionNo, claimNo).map(CancelCaseEntity::toDomain);
    }

    @Override
    public List<CancelCase> lockByState(CancelState state, int limit) {
        return cases.lockByState(state.name(), limit).stream().map(CancelCaseEntity::toDomain).toList();
    }

    @Override
    public List<CancelCase> findByState(CancelState state, int limit) {
        return cases.findByStateOrderByIdAsc(state, Limit.of(limit)).stream().map(CancelCaseEntity::toDomain).toList();
    }

    @Override
    public void update(long optionNo, long claimNo, CancelState state, String tplStatus, Integer fee, String detail,
                       LocalDateTime at) {
        CancelCaseEntity e = cases.findByOrderProductOptionNoAndClaimNo(optionNo, claimNo).orElseThrow();
        e.update(state, tplStatus, fee, detail, at);
        cases.saveAndFlush(e);
    }

    @Override
    public void addRecallTask(long optionNo, long claimNo, String invoiceNo, String reason, LocalDateTime at) {
        if (!recalls.existsByOrderProductOptionNoAndClaimNo(optionNo, claimNo)) {
            recalls.save(new RecallTaskEntity(optionNo, claimNo, invoiceNo, reason, at));
        }
    }

    @Override
    public void record(String targetKey, String reasonCode, String detail, LocalDateTime at) {
        conflicts.save(new ConflictEntity(targetKey, reasonCode, detail == null ? null
                : detail.substring(0, Math.min(detail.length(), 1000)), at));
    }
}
