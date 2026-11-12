package io.github.gumwoo.fulfillment.admin.web;

import io.github.gumwoo.fulfillment.infra.persistence.ReconciliationRunRepository;
import io.github.gumwoo.fulfillment.infra.sqs.SqsQueues;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Limit;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest;
import software.amazon.awssdk.services.sqs.model.PurgeQueueRequest;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

/**
 * 데모 대시보드용 현황 API. 읽기 전용 집계다(운영 화면에서도 쓸 수 있다).
 * 데모 초기화(/admin/demo/reset)는 app.admin.demo-enabled=true일 때만 동작한다.
 */
@RestController
@RequestMapping("/admin")
public class DashboardController {

    private final JdbcTemplate jdbc;
    private final SqsClient sqs;
    private final SqsQueues queues;
    private final ReconciliationRunRepository runs;
    private final boolean demoEnabled;

    public DashboardController(JdbcTemplate jdbc, SqsClient sqs, SqsQueues queues, ReconciliationRunRepository runs,
                               @Value("${app.admin.demo-enabled:false}") boolean demoEnabled) {
        this.jdbc = jdbc;
        this.sqs = sqs;
        this.queues = queues;
        this.runs = runs;
        this.demoEnabled = demoEnabled;
    }

    @GetMapping("/overview")
    public Map<String, Object> overview() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("at", LocalDateTime.now().toString());
        m.put("orderLines", groupCount("select status k, count(*) n from order_line_state group by status"));
        m.put("openClaims", groupCount("select claim_status k, count(*) n from order_line_state where claim_status is not null group by claim_status"));
        m.put("statusApplied", count("select count(*) from status_history"));
        m.put("processedEvents", groupCount("select source k, count(*) n from processed_event group by source"));
        m.put("openConflicts", groupCount("select reason_code k, count(*) n from conflict where resolved_at is null group by reason_code"));
        m.put("openUnlinked", count("select count(*) from unlinked where resolved_at is null"));
        m.put("openRecalls", count("select count(*) from recall_task where resolved_at is null"));
        m.put("cancelCases", groupCount("select state k, count(*) n from cancel_case group by state"));
        m.put("shipments", groupCount("select tpl_status k, count(*) n from shipment where tpl_status is not null group by tpl_status"));
        m.put("invoicePending", count("select count(distinct delivery_no) from shipment where tpl_status = 'RELEASED' and invoice_no is not null and invoice_synced_at is null"));
        m.put("invoiceSynced", count("select count(distinct delivery_no) from shipment where invoice_synced_at is not null"));
        m.put("alertsUnnotified", count("select count(*) from alert where notified_at is null"));
        m.put("notifications", count("select count(*) from notification"));
        m.put("queue", queueDepth(queues.queueUrl()));
        m.put("dlq", queueDepth(queues.dlqUrl()));
        m.put("lastReconciliation", runs.findByOrderByRanAtDesc(Limit.of(1)).stream().findFirst()
                .map(r -> Map.<String, Object>of("ranAt", r.getRanAt().toString(), "compared", r.getCompared(),
                        "mismatches", r.getMismatches()))
                .orElse(null));
        return m;
    }

    /** 최근 대사 실행 (오래된 것부터) */
    @GetMapping("/reconciliation/runs")
    public List<Map<String, Object>> reconciliationRuns(@RequestParam(defaultValue = "60") int limit) {
        List<Map<String, Object>> rows = new java.util.ArrayList<>(runs.findByOrderByRanAtDesc(Limit.of(Math.max(1, Math.min(limit, 500))))
                .stream().map(r -> Map.<String, Object>of("ranAt", r.getRanAt().toString(), "compared", r.getCompared(),
                        "mismatches", r.getMismatches()))
                .toList());
        java.util.Collections.reverse(rows);
        return rows;
    }

    /** 데모 초기화: 우리 쪽 데이터와 큐를 비운다 (mock 쪽은 /_mock/demo/reset). 데모 설정에서만. */
    @PostMapping("/demo/reset")
    public ResponseEntity<Map<String, Object>> resetForDemo() {
        if (!demoEnabled) {
            return ResponseEntity.status(403).body(Map.of("message", "app.admin.demo-enabled=false"));
        }
        List<String> tables = jdbc.queryForList(
                "select table_name from information_schema.tables where table_schema = database() and table_type = 'BASE TABLE'",
                String.class);
        // 세션 변수는 연결마다라서 한 연결 안에서 끄고, 실패해도 되돌린 뒤 풀에 돌려준다
        jdbc.execute((java.sql.Connection c) -> {
            try (java.sql.Statement st = c.createStatement()) {
                st.execute("SET FOREIGN_KEY_CHECKS = 0");
                try {
                    for (String t : tables) {
                        st.execute("TRUNCATE TABLE `" + t + "`");
                    }
                } finally {
                    st.execute("SET FOREIGN_KEY_CHECKS = 1");
                }
            }
            return null;
        });
        for (String url : List.of(queues.queueUrl(), queues.dlqUrl())) {
            try {
                sqs.purgeQueue(PurgeQueueRequest.builder().queueUrl(url).build());
            } catch (software.amazon.awssdk.services.sqs.model.PurgeQueueInProgressException e) {
                // SQS는 큐당 60초에 한 번만 비운다. 직전 초기화가 비우는 중이면 그대로 둔다
            }
        }
        return ResponseEntity.ok(Map.of("truncatedTables", tables.size()));
    }

    private Map<String, Integer> queueDepth(String url) {
        Map<QueueAttributeName, String> a = sqs.getQueueAttributes(GetQueueAttributesRequest.builder().queueUrl(url)
                .attributeNames(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES,
                        QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE,
                        QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_DELAYED).build()).attributesAsStrings()
                .entrySet().stream().collect(java.util.stream.Collectors.toMap(
                        e -> QueueAttributeName.fromValue(e.getKey()), Map.Entry::getValue));
        Map<String, Integer> m = new LinkedHashMap<>();
        m.put("visible", Integer.parseInt(a.getOrDefault(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES, "0")));
        m.put("inFlight", Integer.parseInt(a.getOrDefault(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE, "0")));
        m.put("delayed", Integer.parseInt(a.getOrDefault(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_DELAYED, "0")));
        return m;
    }

    private long count(String sql) {
        Long n = jdbc.queryForObject(sql, Long.class);
        return n == null ? 0 : n;
    }

    private Map<String, Long> groupCount(String sql) {
        Map<String, Long> m = new LinkedHashMap<>();
        jdbc.query(sql, rs -> {
            m.put(rs.getString("k"), rs.getLong("n"));
        });
        return m;
    }
}
