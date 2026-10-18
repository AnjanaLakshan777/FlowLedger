package com.flowledger.payment.outbox;

import com.flowledger.payment.support.FakeBankIntegrationServer;
import com.flowledger.payment.support.FakeIdentityServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The publisher against the real payment database and the local RabbitMQ (compose.yaml). Not
 * @Transactional: claiming, publishing and marking commit separately, as in production. Every
 * test works only on its own company's events, so other rows in the shared database are never
 * claimed or changed.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OutboxPublishingIntegrationTest {

    private static final FakeIdentityServer identityService = FakeIdentityServer.instance();
    private static final FakeBankIntegrationServer bankIntegration = FakeBankIntegrationServer.instance();

    @DynamicPropertySource
    static void serviceUrls(DynamicPropertyRegistry registry) {
        registry.add("flowledger.identity.base-url", identityService::baseUrl);
        registry.add("flowledger.bank-integration.base-url", bankIntegration::baseUrl);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private OutboxPublisher publisher;

    @Autowired
    private OutboxStore store;

    @Autowired
    private OutboxProperties properties;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private AmqpAdmin amqpAdmin;

    @Autowired
    private TopicExchange paymentExchange;

    private UUID companyId;
    private UUID bankAccountId;
    private String queue;

    @BeforeEach
    void setUp() {
        companyId = UUID.randomUUID();
        bankAccountId = UUID.randomUUID();
        queue = "outbox-test-" + UUID.randomUUID();
        identityService.reset();
        bankIntegration.reset();
        identityService.grant(companyId, "FINANCE_CONTROLLER");
        bankIntegration.currentAccount(companyId, bankAccountId);
        amqpAdmin.declareExchange(paymentExchange);
        amqpAdmin.declareQueue(new Queue(queue, true, false, false));
    }

    // Audit history is append-only, so committed test data is removed with the trigger bypass
    // available to the local superuser. Test cleanup only, and only this test's company.
    @AfterEach
    void deleteTestData() {
        amqpAdmin.deleteQueue(queue);
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbcTemplate.execute("SET LOCAL session_replication_role = replica");
            jdbcTemplate.update("DELETE FROM outbox_events WHERE payload ->> 'companyId' = ?", companyId.toString());
            jdbcTemplate.update("DELETE FROM payment_audit_events WHERE company_id = ?", companyId);
            jdbcTemplate.update("DELETE FROM cheques WHERE company_id = ?", companyId);
        });
    }

    @Test
    void shouldPublishANewChequeEventOnceWithoutPrivateFields() throws Exception {
        bind("cheque.#");
        UUID chequeId = addCheque("100201", "Private Payee Name", "private note");
        UUID eventId = eventIds().getFirst();

        OutboxPublisher.Result result = publisher.publishDue(companyId);

        assertThat(result.published()).isEqualTo(1);
        Message message = rabbitTemplate.receive(queue, 5000);
        assertThat(message).isNotNull();
        assertThat(message.getMessageProperties().getMessageId()).isEqualTo(eventId.toString());
        assertThat(message.getMessageProperties().getReceivedRoutingKey()).isEqualTo("cheque.issued");
        assertThat(message.getMessageProperties().getReceivedExchange()).isEqualTo(properties.exchange());
        assertThat(message.getMessageProperties().getReceivedDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
        assertThat(message.getMessageProperties().<String>getHeader("eventType")).isEqualTo("ChequeIssued");
        assertThat(message.getMessageProperties().<Integer>getHeader("schemaVersion")).isEqualTo(1);
        assertThat(message.getMessageProperties().<String>getHeader("aggregateId")).isEqualTo(chequeId.toString());
        assertThat(message.getMessageProperties().<String>getHeader("companyId")).isEqualTo(companyId.toString());

        String body = new String(message.getBody(), StandardCharsets.UTF_8);
        assertThat(body).contains("\"chequeId\": \"" + chequeId + "\"").contains("\"chequeNumber\": \"100201\"")
                .contains("\"status\": \"ISSUED\"");
        assertThat(body).doesNotContain("Private Payee Name").doesNotContain("private note")
                .doesNotContain("payeeName").doesNotContain("notes");

        Map<String, Object> row = row(eventId);
        assertThat(row.get("published_at")).isNotNull();
        assertThat(row.get("attempt_count")).isEqualTo(1);
        assertThat(row.get("last_error")).isNull();
        assertThat(rabbitTemplate.receive(queue, 500)).isNull();
    }

    // Rows from before the activation boundary are never claimed, sent or changed.
    @Test
    void shouldLeaveRowsFromBeforeTheActivationBoundaryUntouched() {
        bind("cheque.#");
        String othersBefore = fingerprintOfUnpublishableRows();
        UUID historical = insertEvent(UUID.randomUUID(), "ChequeIssued", false, "2026-10-15T09:00:00Z");

        OutboxPublisher.Result result = publisher.publishDue(companyId);

        assertThat(result.claimed()).isZero();
        assertThat(rabbitTemplate.receive(queue, 500)).isNull();
        Map<String, Object> row = row(historical);
        assertThat(row.get("published_at")).isNull();
        assertThat(row.get("attempt_count")).isEqualTo(0);
        assertThat(row.get("next_attempt_at")).isNull();
        assertThat(row.get("last_error")).isNull();
        jdbcTemplate.update("DELETE FROM outbox_events WHERE id = ?", historical);
        assertThat(fingerprintOfUnpublishableRows()).isEqualTo(othersBefore);
    }

    @Test
    void shouldMakeNewRowsPublishableByDefault() {
        UUID id = insertEvent(UUID.randomUUID(), "ChequeIssued", null, "2026-10-15T09:00:00Z");

        assertThat(row(id).get("publishable")).isEqualTo(true);
    }

    @Test
    void shouldKeepAnUnroutableEventPendingAndPublishItOnceAQueueIsBound() {
        UUID id = insertEvent(UUID.randomUUID(), "ChequeIssued", null, "2026-10-15T09:00:00Z");

        // no queue bound for cheque.issued: the broker returns the message
        OutboxPublisher.Result first = publisher.publishDue(companyId);
        assertThat(first.failed()).isEqualTo(1);
        Map<String, Object> pending = row(id);
        assertThat(pending.get("published_at")).isNull();
        assertThat(pending.get("attempt_count")).isEqualTo(1);
        assertThat((String) pending.get("last_error")).startsWith("Unroutable: 312 NO_ROUTE");
        // retried after 2.5 to 5 s (first attempt, equal jitter)
        double delaySeconds = jdbcTemplate.queryForObject(
                "SELECT EXTRACT(EPOCH FROM next_attempt_at - now()) FROM outbox_events WHERE id = ?", Double.class, id);
        assertThat(delaySeconds).isBetween(2.0, 5.0);
        assertThat(publisher.publishDue(companyId).claimed()).as("not claimed again before it is due").isZero();

        bind("cheque.issued");
        jdbcTemplate.update("UPDATE outbox_events SET next_attempt_at = now() - interval '1 second' WHERE id = ?", id);
        OutboxPublisher.Result retry = publisher.publishDue(companyId);

        assertThat(retry.published()).isEqualTo(1);
        Map<String, Object> published = row(id);
        assertThat(published.get("published_at")).isNotNull();
        assertThat(published.get("attempt_count")).isEqualTo(2);
        assertThat(published.get("last_error")).isNull();
        assertThat(rabbitTemplate.receive(queue, 5000).getMessageProperties().getMessageId()).isEqualTo(id.toString());
    }

    @Test
    void shouldKeepEventsPendingWhileTheBrokerCannotBeReached() throws IOException {
        bind("cheque.#");
        UUID id = insertEvent(UUID.randomUUID(), "ChequeIssued", null, "2026-10-15T09:00:00Z");
        CachingConnectionFactory deadBroker = new CachingConnectionFactory("localhost", unusedPort());
        deadBroker.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
        deadBroker.setPublisherReturns(true);
        deadBroker.setConnectionTimeout(1000);
        RabbitTemplate unreachable = new RabbitTemplate(deadBroker);
        unreachable.setMandatory(true);
        try {
            OutboxPublisher.Result result = new OutboxPublisher(store, unreachable, properties).publishDue(companyId);

            assertThat(result.failed()).isEqualTo(1);
            Map<String, Object> row = row(id);
            assertThat(row.get("published_at")).isNull();
            assertThat((String) row.get("last_error")).startsWith("Broker unavailable: AmqpConnectException");
            assertThat(rabbitTemplate.receive(queue, 500)).isNull();
        } finally {
            deadBroker.destroy();
        }
    }

    // The broker accepted the message, but the process "stopped" before marking it published:
    // after the lease the event is sent again with the same message ID (at-least-once).
    @Test
    void shouldResendWithTheSameMessageIdAfterACrashBetweenAckAndMarking() {
        bind("cheque.#");
        UUID id = insertEvent(UUID.randomUUID(), "ChequeIssued", null, "2026-10-15T09:00:00Z");
        AtomicBoolean crashOnce = new AtomicBoolean(true);
        OutboxStore crashingStore = new OutboxStore(jdbcTemplate) {
            @Override
            public void markPublished(UUID eventId) {
                if (crashOnce.getAndSet(false)) {
                    throw new IllegalStateException("simulated crash after the broker ack");
                }
                super.markPublished(eventId);
            }
        };
        OutboxPublisher crashing = new OutboxPublisher(crashingStore, rabbitTemplate, properties);

        try {
            crashing.publishDue(companyId);
        } catch (IllegalStateException expected) {
            // the publisher run died here
        }
        Message first = rabbitTemplate.receive(queue, 5000);
        assertThat(first.getMessageProperties().getMessageId()).isEqualTo(id.toString());
        assertThat(row(id).get("published_at")).isNull();
        assertThat(crashing.publishDue(companyId).claimed()).as("still leased").isZero();

        jdbcTemplate.update("UPDATE outbox_events SET next_attempt_at = now() - interval '1 second' WHERE id = ?", id);
        assertThat(crashing.publishDue(companyId).published()).isEqualTo(1);

        Message duplicate = rabbitTemplate.receive(queue, 5000);
        assertThat(duplicate.getMessageProperties().getMessageId()).isEqualTo(id.toString());
        assertThat(duplicate.getBody()).isEqualTo(first.getBody());
        assertThat(row(id).get("published_at")).isNotNull();
        assertThat(row(id).get("attempt_count")).isEqualTo(2);
    }

    @Test
    void shouldPublishEachEventOnceWithTwoPublishersAtTheSameTime() throws Exception {
        bind("cheque.#");
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            ids.add(insertEvent(UUID.randomUUID(), "ChequeIssued", null, "2026-10-15T09:00:%02dZ".formatted(i)));
        }
        OutboxProperties smallBatches = new OutboxProperties(true, 4, properties.pollInterval(), properties.lease(),
                properties.confirmTimeout(), properties.initialBackoff(), properties.maxBackoff(), properties.exchange());
        Callable<Integer> drain = () -> {
            OutboxPublisher worker = new OutboxPublisher(store, rabbitTemplate, smallBatches);
            int published = 0;
            OutboxPublisher.Result result;
            do {
                result = worker.publishDue(companyId);
                published += result.published();
            } while (result.claimed() > 0);
            return published;
        };

        ExecutorService executor = Executors.newFixedThreadPool(2);
        int total = 0;
        try {
            for (Future<Integer> future : executor.invokeAll(List.of(drain, drain), 60, TimeUnit.SECONDS)) {
                total += future.get();
            }
        } finally {
            executor.shutdownNow();
        }

        assertThat(total).isEqualTo(30);
        Set<String> received = new HashSet<>();
        Message message;
        while ((message = rabbitTemplate.receive(queue, 1000)) != null) {
            assertThat(received.add(message.getMessageProperties().getMessageId())).as("no duplicate delivery").isTrue();
        }
        assertThat(received).hasSize(30).containsExactlyInAnyOrderElementsOf(ids.stream().map(UUID::toString).toList());
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM outbox_events
                WHERE payload ->> 'companyId' = ? AND published_at IS NOT NULL AND attempt_count = 1
                """, Integer.class, companyId.toString())).isEqualTo(30);
    }

    // One cheque's events go out in order: a later event waits while an earlier one is pending.
    @Test
    void shouldNotOvertakeAPendingEarlierEventOfTheSameCheque() {
        bind("cheque.#");
        UUID chequeId = UUID.randomUUID();
        UUID issued = insertEvent(chequeId, "ChequeIssued", null, "2026-10-15T09:00:00Z");
        UUID cancelled = insertEvent(chequeId, "ChequeCancelled", null, "2026-10-15T09:00:05Z");
        jdbcTemplate.update("UPDATE outbox_events SET next_attempt_at = now() + interval '1 hour' WHERE id = ?", issued);

        assertThat(publisher.publishDue(companyId).claimed()).isZero();

        jdbcTemplate.update("UPDATE outbox_events SET next_attempt_at = NULL WHERE id = ?", issued);
        assertThat(publisher.publishDue(companyId).published()).isEqualTo(1);
        assertThat(publisher.publishDue(companyId).published()).isEqualTo(1);
        assertThat(rabbitTemplate.receive(queue, 5000).getMessageProperties().getMessageId()).isEqualTo(issued.toString());
        assertThat(rabbitTemplate.receive(queue, 5000).getMessageProperties().getMessageId()).isEqualTo(cancelled.toString());
    }

    // A refused cheque change writes no event, so there is nothing to publish.
    @Test
    void shouldPublishNothingForARolledBackChange() throws Exception {
        bind("cheque.#");
        addCheque("100301", "Lanka Tiles", "");
        postCheque("100301", "Lanka Tiles").andExpect(status().isConflict());

        assertThat(eventIds()).hasSize(1);
        assertThat(publisher.publishDue(companyId).published()).isEqualTo(1);
        assertThat(rabbitTemplate.receive(queue, 5000)).isNotNull();
        assertThat(rabbitTemplate.receive(queue, 500)).isNull();
    }

    private void bind(String routingKey) {
        Binding binding = BindingBuilder.bind(new Queue(queue, true, false, false)).to(paymentExchange).with(routingKey);
        amqpAdmin.declareBinding(binding);
    }

    private UUID addCheque(String number, String payee, String notes) throws Exception {
        String body = postCheque(number, payee, notes).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(com.jayway.jsonpath.JsonPath.read(body, "$.id"));
    }

    private org.springframework.test.web.servlet.ResultActions postCheque(String number, String payee) throws Exception {
        return postCheque(number, payee, "");
    }

    private org.springframework.test.web.servlet.ResultActions postCheque(String number, String payee, String notes) throws Exception {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Colombo"));
        return mockMvc.perform(post("/api/v1/payment/companies/" + companyId + "/cheques")
                .with(jwt().jwt(jwt -> jwt.subject(UUID.randomUUID().toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"chequeNumber": "%s", "payeeName": "%s", "category": "SUPPLIER_PAYMENT", "amount": 45000.00,
                         "issueDate": "%s", "expectedClearingDate": "%s", "notes": "%s"}
                        """.formatted(number, payee, today, today.plusDays(10), notes)));
    }

    // publishable null means: use the column default.
    private UUID insertEvent(UUID aggregateId, String eventType, Boolean publishable, String createdAt) {
        String payload = "{\"chequeId\": \"" + aggregateId + "\", \"companyId\": \"" + companyId + "\", \"amount\": \"100.00\"}";
        return publishable == null
                ? jdbcTemplate.queryForObject("""
                        INSERT INTO outbox_events (aggregate_type, aggregate_id, event_type, payload, created_at)
                        VALUES ('Cheque', ?, ?, ?::jsonb, ?::timestamptz) RETURNING id
                        """, UUID.class, aggregateId, eventType, payload, createdAt)
                : jdbcTemplate.queryForObject("""
                        INSERT INTO outbox_events (aggregate_type, aggregate_id, event_type, payload, created_at, publishable)
                        VALUES ('Cheque', ?, ?, ?::jsonb, ?::timestamptz, ?) RETURNING id
                        """, UUID.class, aggregateId, eventType, payload, createdAt, publishable);
    }

    private List<UUID> eventIds() {
        return jdbcTemplate.queryForList("SELECT id FROM outbox_events WHERE payload ->> 'companyId' = ? ORDER BY created_at",
                UUID.class, companyId.toString());
    }

    private Map<String, Object> row(UUID id) {
        return jdbcTemplate.queryForMap("SELECT * FROM outbox_events WHERE id = ?", id);
    }

    // Every column of every row from before the activation boundary, outside this test's company.
    private String fingerprintOfUnpublishableRows() {
        return jdbcTemplate.queryForObject("""
                SELECT count(*) || ':' || coalesce(md5(string_agg(concat_ws('|', id, aggregate_type, aggregate_id, event_type,
                       schema_version, payload::text, created_at, published_at, attempt_count, next_attempt_at, last_error),
                       chr(10) ORDER BY id)), '')
                FROM outbox_events WHERE NOT publishable AND payload ->> 'companyId' IS DISTINCT FROM ?
                """, String.class, companyId.toString());
    }

    private static int unusedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
