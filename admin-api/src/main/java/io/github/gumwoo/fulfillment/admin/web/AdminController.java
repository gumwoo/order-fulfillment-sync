package io.github.gumwoo.fulfillment.admin.web;

import io.github.gumwoo.fulfillment.infra.persistence.AdminActionEntity;
import io.github.gumwoo.fulfillment.infra.query.AdminQueryRepository;
import io.github.gumwoo.fulfillment.infra.query.AdminQueryRepository.ConflictRow;
import io.github.gumwoo.fulfillment.infra.query.AdminQueryRepository.RecallRow;
import io.github.gumwoo.fulfillment.infra.query.AdminQueryRepository.UnlinkedRow;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * CS 어드민 API (설계 4.9). 인증은 범위 밖이라 처리자는 X-Actor 헤더로 받는다(README에 명시).
 * 재처리는 Idempotency-Key 헤더가 필수다.
 */
@RestController
@RequestMapping("/admin")
public class AdminController {

    static final int MAX_ROWS = 500;

    private final AdminQueryRepository query;
    private final ReprocessService reprocess;

    public AdminController(AdminQueryRepository query, ReprocessService reprocess) {
        this.query = query;
        this.reprocess = reprocess;
    }

    /** 미연동 목록. status: OPEN | RESOLVED | (없음=전체) */
    @GetMapping("/unlinked")
    public List<UnlinkedRow> unlinked(@RequestParam(required = false) String reasonCode,
                                      @RequestParam(required = false) String status) {
        return query.unlinked(reasonCode, checkStatus(status), MAX_ROWS);
    }

    @GetMapping("/conflicts")
    public List<ConflictRow> conflicts(@RequestParam(required = false) String reasonCode,
                                       @RequestParam(required = false) String status) {
        return query.conflicts(reasonCode, checkStatus(status), MAX_ROWS);
    }

    /** CS 회수 안내 목록 (피킹 이후 취소) */
    @GetMapping("/recalls")
    public List<RecallRow> recalls(@RequestParam(required = false) String status) {
        return query.recalls(checkStatus(status), MAX_ROWS);
    }

    /** 오타가 조용히 전체 조회가 되지 않게 한다 */
    private static String checkStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        if (!status.equals("OPEN") && !status.equals("RESOLVED")) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "status는 OPEN 또는 RESOLVED");
        }
        return status;
    }

    @PostMapping("/unlinked/{id}/reprocess")
    public ResponseEntity<?> reprocess(@PathVariable long id,
                                       @RequestHeader("Idempotency-Key") String requestId,
                                       @RequestHeader("X-Actor") String actor) {
        ReprocessService.Outcome outcome;
        try {
            outcome = reprocess.reprocessUnlinked(id, requestId, actor);
        } catch (ReprocessService.RequestIdReusedException e) {
            return ResponseEntity.status(409).body(java.util.Map.of("message", e.getMessage()));
        }
        if (AdminActionEntity.FAILED.equals(outcome.status())) {
            return ResponseEntity.status(503).body(outcome);
        }
        return ResponseEntity.ok(outcome);
    }

    /** 처리 이력. targetKey 예: unlinked:12 */
    @GetMapping("/history")
    public List<HistoryRow> history(@RequestParam(required = false) String targetKey) {
        return query.history(targetKey, MAX_ROWS).stream().map(HistoryRow::of).toList();
    }

    public record HistoryRow(String requestId, String actor, String action, String targetKey, String status,
                             String result, String detail, java.time.LocalDateTime createdAt,
                             java.time.LocalDateTime finishedAt) {
        static HistoryRow of(AdminActionEntity a) {
            return new HistoryRow(a.getRequestId(), a.getActor(), a.getAction(), a.getTargetKey(), a.getStatus(),
                    a.getResult(), a.getDetail(), a.getCreatedAt(), a.getFinishedAt());
        }
    }
}
