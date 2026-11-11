package io.github.gumwoo.fulfillment.scenario.support;

import java.net.URI;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.PurgeQueueRequest;
import software.amazon.awssdk.services.sqs.model.QueueDoesNotExistException;

/**
 * 시나리오 테스트 환경. 컨테이너(MySQL, Redis, LocalStack)는 JVM당 한 번 띄우고,
 * 실행 모듈(Spring Boot 컨텍스트)은 테스트가 요구하는 것만 켠다. 이미지는 docker-compose.yml과 같은 태그를 쓴다.
 */
public final class ScenarioStack {

    public static final String QUEUE = "order-events";
    public static final String DLQ = "order-events-dlq";

    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.4.13"))
            .withDatabaseName("fulfillment")
            .withUsername("fulfillment")
            .withPassword("fulfillment")
            .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_0900_ai_ci");
    private static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4.12-alpine"))
            .withExposedPorts(6379);
    private static final LocalStackContainer LOCALSTACK =
            new LocalStackContainer(DockerImageName.parse("localstack/localstack:4.14.0"))
                    .withServices(LocalStackContainer.Service.SQS);

    private static final ScenarioStack INSTANCE = new ScenarioStack();

    private final Map<App, ConfigurableApplicationContext> running = new EnumMap<>(App.class);
    private final Map<App, Map<String, Object>> runningProps = new EnumMap<>(App.class);
    private JdbcTemplate jdbc;
    private SqsClient sqs;
    private final int mockPort = freePort();

    private static int freePort() {
        try (java.net.ServerSocket s = new java.net.ServerSocket(0)) {
            return s.getLocalPort();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private ScenarioStack() {
    }

    public static synchronized ScenarioStack get() {
        INSTANCE.startContainers();
        return INSTANCE;
    }

    private void startContainers() {
        if (jdbc != null) {
            return;
        }
        MYSQL.start();
        REDIS.start();
        LOCALSTACK.start();
        DriverManagerDataSource ds = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        jdbc = new JdbcTemplate(ds);
        sqs = SqsClient.builder()
                .endpointOverride(LOCALSTACK.getEndpoint())
                .region(Region.of(LOCALSTACK.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .build();
        Runtime.getRuntime().addShutdownHook(new Thread(this::stopAll));
    }

    /** 공통 설정: 컨테이너 주소, 랜덤 포트, 짧은 큐 대기 */
    public Map<String, Object> baseProperties() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("server.port", "0");
        p.put("spring.datasource.url", MYSQL.getJdbcUrl() + "?serverTimezone=Asia/Seoul");
        p.put("spring.datasource.username", MYSQL.getUsername());
        p.put("spring.datasource.password", MYSQL.getPassword());
        p.put("spring.data.redis.host", REDIS.getHost());
        p.put("spring.data.redis.port", String.valueOf(REDIS.getMappedPort(6379)));
        p.put("app.sqs.endpoint", LOCALSTACK.getEndpoint().toString());
        p.put("app.sqs.region", LOCALSTACK.getRegion());
        p.put("app.sqs.create-queues", "true");
        p.put("app.sqs.wait-time-seconds", "1");
        p.put("app.sqs.visibility-timeout-seconds", "5");
        p.put("spring.main.banner-mode", "off");
        p.put("logging.level.root", "WARN");
        p.put("app.shopby.base-url", "http://localhost:" + mockPort);
        // 스케줄 작업은 테스트가 직접 호출한다
        p.put("app.reconciler.scheduling-enabled", "false");
        return p;
    }

    /** mock 주소를 다른 앱 설정에 미리 넣기 위해 mock은 고정 포트로 띄운다 */
    public int mockPort() {
        return mockPort;
    }

    public String inboundWebhookUrl() {
        return baseUrl(App.INBOUND).resolve("/webhooks/shopby").toString();
    }

    /**
     * 요구한 실행 모듈만 켜진 상태로 맞춘다. 설정이 바뀐 모듈은 다시 띄운다.
     * 순서: 요구 목록에 없는 것을 먼저 끄고, 목록 순서대로 켠다.
     */
    public synchronized void use(Map<App, Map<String, Object>> required) {
        for (App app : new ArrayList<>(running.keySet())) {
            if (!required.containsKey(app) || !required.get(app).equals(runningProps.get(app))) {
                stop(app);
            }
        }
        for (Map.Entry<App, Map<String, Object>> e : required.entrySet()) {
            if (!running.containsKey(e.getKey())) {
                start(e.getKey(), e.getValue());
            }
        }
    }

    public void use(Set<App> apps) {
        Map<App, Map<String, Object>> m = new LinkedHashMap<>();
        for (App app : App.values()) {
            if (apps.contains(app)) {
                m.put(app, Map.of());
            }
        }
        use(m);
    }

    private void start(App app, Map<String, Object> extra) {
        Map<String, Object> props = baseProperties();
        if (app == App.MOCK) {
            props.put("server.port", String.valueOf(mockPort));
        }
        props.putAll(extra);
        List<String> args = new ArrayList<>();
        props.forEach((k, v) -> args.add("--" + k + "=" + v));
        ConfigurableApplicationContext ctx = new SpringApplicationBuilder(app.mainClass)
                .properties("spring.config.name=" + app.configName)
                .run(args.toArray(String[]::new));
        running.put(app, ctx);
        runningProps.put(app, Map.copyOf(extra));
    }

    public synchronized void stop(App app) {
        ConfigurableApplicationContext ctx = running.remove(app);
        runningProps.remove(app);
        if (ctx != null) {
            ctx.close();
        }
    }

    private synchronized void stopAll() {
        for (App app : new ArrayList<>(running.keySet())) {
            stop(app);
        }
    }

    public ConfigurableApplicationContext context(App app) {
        ConfigurableApplicationContext ctx = running.get(app);
        if (ctx == null) {
            throw new IllegalStateException(app + " 가 실행 중이 아니다. use()로 먼저 켠다");
        }
        return ctx;
    }

    public URI baseUrl(App app) {
        String port = context(app).getEnvironment().getProperty("local.server.port");
        return URI.create("http://localhost:" + port);
    }

    public <T> T bean(App app, Class<T> type) {
        return context(app).getBean(type);
    }

    public JdbcTemplate jdbc() {
        return jdbc;
    }

    public SqsClient sqs() {
        return sqs;
    }

    public DataSource dataSource() {
        return jdbc.getDataSource();
    }

    public String queueUrl(String name) {
        return sqs.getQueueUrl(GetQueueUrlRequest.builder().queueName(name).build()).queueUrl();
    }

    /**
     * 처리가 끝날 때까지 기다린다: outbox에 PENDING이 없고, 큐(대기·처리 중·지연)가 연속 3번(1초 간격) 비어 있을 때.
     * 큐 수치는 근사값(Approximate*)이라 한 번 0으로 끝내지 않는다.
     */
    public void awaitQueueDrained(java.time.Duration atMost) {
        String url = queueUrl(QUEUE);
        int[] zeroStreak = {0};
        org.awaitility.Awaitility.await().atMost(atMost).pollInterval(java.time.Duration.ofSeconds(1)).until(() -> {
            Long pending = jdbc.queryForObject(
                    "select count(*) from information_schema.tables where table_schema='fulfillment' and table_name='outbox'",
                    Long.class) == 0 ? 0L
                    : jdbc.queryForObject("select count(*) from outbox where status = 'PENDING'", Long.class);
            boolean empty = pending == 0 && SqsProbe.approximateTotal(sqs, url) == 0;
            zeroStreak[0] = empty ? zeroStreak[0] + 1 : 0;
            return zeroStreak[0] >= 3;
        });
    }

    /** 테스트 사이 상태 초기화: 모든 테이블 비우기, 큐 비우기 */
    public synchronized void reset() {
        List<String> tables = jdbc.queryForList(
                "select table_name from information_schema.tables where table_schema = 'fulfillment' and table_type = 'BASE TABLE'",
                String.class);
        jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
        for (String t : tables) {
            jdbc.execute("TRUNCATE TABLE `" + t + "`");
        }
        jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
        for (String q : List.of(QUEUE, DLQ)) {
            try {
                sqs.purgeQueue(PurgeQueueRequest.builder().queueUrl(queueUrl(q)).build());
            } catch (QueueDoesNotExistException ignored) {
                // 아직 어떤 모듈도 큐를 만들지 않았다
            }
        }
    }
}
