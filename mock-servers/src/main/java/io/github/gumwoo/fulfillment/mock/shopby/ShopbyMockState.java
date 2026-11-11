package io.github.gumwoo.fulfillment.mock.shopby;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** mock 샵바이의 주문 상태 저장소와 JSON 표현 (공개 스펙의 필드 이름만 쓴다). */
@Component
public class ShopbyMockState {

    static final DateTimeFormatter YMDT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Map<Long, MockOption> options = new ConcurrentHashMap<>();
    private final ObjectMapper mapper;
    private final Clock clock;

    public ShopbyMockState(ObjectMapper mapper, Clock clock) {
        this.mapper = mapper;
        this.clock = clock;
    }

    public LocalDateTime now() {
        return LocalDateTime.now(clock).withNano(0);
    }

    public synchronized void put(MockOption o) {
        options.put(o.orderProductOptionNo, o);
    }

    public Optional<MockOption> find(long optionNo) {
        return Optional.ofNullable(options.get(optionNo));
    }

    public List<MockOption> all() {
        return new ArrayList<>(options.values());
    }

    public synchronized void reset() {
        options.clear();
    }

    /** GET /orders (STATUS_CHANGE): statusChangeYmdt가 구간 안인 옵션의 주문을 주문번호 내림차순으로 페이지 */
    public ObjectNode searchByStatusChange(LocalDateTime start, LocalDateTime end, int pageNumber, int pageSize) {
        Map<String, List<MockOption>> byOrder = options.values().stream()
                .filter(o -> o.statusChangeYmdt != null && !o.statusChangeYmdt.isBefore(start) && !o.statusChangeYmdt.isAfter(end))
                .map(o -> o.orderNo).distinct()
                .sorted(Comparator.reverseOrder())
                .collect(Collectors.toMap(no -> no, no -> optionsOf(no), (a, b) -> a, LinkedHashMap::new));
        List<String> orderNos = new ArrayList<>(byOrder.keySet());
        int from = Math.max(0, (pageNumber - 1) * pageSize);
        int to = Math.min(orderNos.size(), from + pageSize);
        ObjectNode root = mapper.createObjectNode();
        root.put("totalCount", orderNos.size());
        ArrayNode contents = root.putArray("contents");
        for (String orderNo : from < to ? orderNos.subList(from, to) : List.<String>of()) {
            contents.add(orderJson(orderNo, byOrder.get(orderNo)));
        }
        return root;
    }

    private List<MockOption> optionsOf(String orderNo) {
        return options.values().stream().filter(o -> o.orderNo.equals(orderNo))
                .sorted(Comparator.comparingLong(o -> o.orderProductOptionNo)).toList();
    }

    private ObjectNode orderJson(String orderNo, List<MockOption> opts) {
        ObjectNode order = mapper.createObjectNode();
        order.put("orderNo", orderNo);
        putTime(order, "orderYmdt", opts.get(0).orderYmdt);
        ArrayNode groups = order.putArray("deliveryGroups");
        Map<Long, List<MockOption>> byDelivery = opts.stream()
                .collect(Collectors.groupingBy(o -> o.deliveryNo, LinkedHashMap::new, Collectors.toList()));
        byDelivery.forEach((deliveryNo, inGroup) -> {
            ObjectNode g = groups.addObject();
            g.put("deliveryNo", deliveryNo);
            MockOption first = inGroup.get(0);
            g.put("deliveryCompanyType", first.deliveryCompanyType);
            g.put("invoiceNo", first.invoiceNo);
            ArrayNode products = g.putArray("orderProducts");
            Map<String, List<MockOption>> byProduct = inGroup.stream()
                    .collect(Collectors.groupingBy(o -> String.valueOf(o.productName), LinkedHashMap::new, Collectors.toList()));
            byProduct.forEach((productName, po) -> {
                ObjectNode p = products.addObject();
                p.put("productName", productName);
                ArrayNode oos = p.putArray("orderProductOptions");
                po.forEach(o -> oos.add(optionJson(o, false)));
            });
        });
        return order;
    }

    /** 옵션 JSON. webhook=true면 CHANGE_ORDER_STATUS 원소 형태(가이드 샘플 필드), 아니면 주문조회 주문옵션 형태 */
    public ObjectNode optionJson(MockOption o, boolean webhook) {
        ObjectNode n = mapper.createObjectNode();
        if (webhook) {
            n.put("eventType", "CHANGE_ORDER_STATUS");
            n.put("orderNo", o.orderNo);
            n.put("deliveryNo", o.deliveryNo);
            n.put("productName", o.productName);
            n.put("deliveryCompanyType", o.deliveryCompanyType);
            n.put("invoiceNo", o.invoiceNo);
            n.put("requestChannelType", "SERVER");
        }
        n.put("orderProductOptionNo", o.orderProductOptionNo);
        n.put("optionName", o.optionName);
        n.put("optionValue", o.optionValue);
        n.put("orderCnt", o.orderCnt);
        n.put("orderStatusType", o.orderStatusType);
        n.put("claimStatusType", o.claimStatusType);
        if (o.claimNo == null) {
            n.putNull("claimNo");
        } else {
            n.put("claimNo", o.claimNo);
        }
        putTime(n, "orderYmdt", o.orderYmdt);
        putTime(n, "payYmdt", o.payYmdt);
        putTime(n, "orderAcceptYmdt", o.orderAcceptYmdt);
        putTime(n, "releaseReadyYmdt", o.releaseReadyYmdt);
        putTime(n, "releaseYmdt", o.releaseYmdt);
        putTime(n, "deliveryCompleteYmdt", o.deliveryCompleteYmdt);
        putTime(n, "buyConfirmYmdt", o.buyConfirmYmdt);
        if (!webhook) {
            putTime(n, "statusChangeYmdt", o.statusChangeYmdt);
            n.put("holdDeliveryYn", o.holdDelivery ? "Y" : "N");
        }
        return n;
    }

    private static void putTime(ObjectNode n, String field, LocalDateTime t) {
        if (t == null) {
            n.putNull(field);
        } else {
            n.put(field, t.format(YMDT));
        }
    }
}
