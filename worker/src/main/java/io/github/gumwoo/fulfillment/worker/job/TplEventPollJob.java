package io.github.gumwoo.fulfillment.worker.job;

import io.github.gumwoo.fulfillment.core.port.TplClient;
import io.github.gumwoo.fulfillment.core.port.TplClient.Event;
import io.github.gumwoo.fulfillment.core.port.TplClient.EventPage;
import io.github.gumwoo.fulfillment.core.port.queue.MessageQueue;
import io.github.gumwoo.fulfillment.core.port.queue.MessageType;
import io.github.gumwoo.fulfillment.core.port.queue.QueueMessage;
import io.github.gumwoo.fulfillment.infra.json.TplEventJson;
import io.github.gumwoo.fulfillment.infra.persistence.JpaInvoiceStore;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 3PL 출고 이벤트를 순번(cursor) 이후로 가져와 TPL_EVENT 메시지로 큐에 넣는다 (설계 4.7: 송장 주기 폴링 흉내).
 * 큐에 넣은 뒤에 cursor를 옮긴다. 그 사이에 죽으면 같은 이벤트가 한 번 더 들어가지만 이벤트 반영은 멱등이다
 * (상태·송장 덮어쓰기). 여러 인스턴스에서 동시에 돌리면 중복만 생긴다.
 */
@Component
public class TplEventPollJob {

    static final String CURSOR = "TPL_EVENTS";
    static final int LIMIT = 500;

    private final TplClient tpl;
    private final MessageQueue queue;
    private final TplEventJson json;
    private final JpaInvoiceStore store;
    private final TransactionTemplate tx;

    public TplEventPollJob(TplClient tpl, MessageQueue queue, TplEventJson json, JpaInvoiceStore store,
                           TransactionTemplate tx) {
        this.tpl = tpl;
        this.queue = queue;
        this.json = json;
        this.store = store;
        this.tx = tx;
    }

    /** @return 큐에 넣은 이벤트 수 */
    public int poll() {
        int total = 0;
        while (true) {
            long after = tx.execute(s -> store.getCursor(CURSOR));
            EventPage page = tpl.events(after, LIMIT);
            for (Event e : page.events()) {
                queue.send(QueueMessage.of(MessageType.TPL_EVENT, json.write(e), "TPL"));
            }
            total += page.events().size();
            if (page.events().isEmpty()) {
                return total;
            }
            tx.executeWithoutResult(s -> store.saveCursor(CURSOR, page.lastSeq()));
            if (page.events().size() < LIMIT) {
                return total;
            }
        }
    }
}
