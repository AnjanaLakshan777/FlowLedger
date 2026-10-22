package com.flowledger.forecast.events;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageListenerContainer;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.IntFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * The cheque-event consumer against the local forecast PostgreSQL database and the Compose
 * RabbitMQ broker (credentials from .env). The class declares its own exchange, queue, dead-letter
 * exchange and dead-letter queue with random names and deletes them afterwards, so it never touches
 * the real flowledger.payment exchange or the forecast queue. Not @Transactional: each event
 * commits in its own transaction, as in production.
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(OutputCaptureExtension.class)
class ChequeEventConsumerIntegrationTest {

    private static final String SUFFIX = UUID.randomUUID().toString();
    private static final String EXCHANGE = "test.payment." + SUFFIX;
    private static final String QUEUE = "test.forecast-cheques." + SUFFIX;
    private static final String DLX = QUEUE + ".dlx";
    private static final String DLQ = QUEUE + ".dlq";
    private static final int MAX_ATTEMPTS = 3;
    private static final int DELIVERY_LIMIT = 2;

    @DynamicPropertySource
    static void topology(DynamicPropertyRegistry registry) {
        registry.add("flowledger.cheque-events.enabled", () -> "true");
        registry.add("flowledger.cheque-events.exchange", () -> EXCHANGE);
        registry.add("flowledger.cheque-events.queue", () -> QUEUE);
        registry.add("flowledger.cheque-events.dead-letter-exchange", () -> DLX);
        registry.add("flowledger.cheque-events.dead-letter-queue", () -> DLQ);
        registry.add("flowledger.cheque-events.max-attempts", () -> MAX_ATTEMPTS);
        registry.add("flowledger.cheque-events.delivery-limit", () -> DELIVERY_LIMIT);
    }

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private RabbitAdmin rabbitAdmin;

    @Autowired
    private CachingConnectionFactory connectionFactory;

    @Autowired
    private RabbitListenerEndpointRegistry listenerRegistry;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private ChequeEventProcessor processor;

    @Autowired
    private ChequeEventListener listener;

    @Autowired
    private ChequeEventParser parser;

    @MockitoSpyBean
    private ChequeProjectionStore store;

    private final Set<UUID> companyIds = new HashSet<>();
    private final Set<UUID> eventIds = new HashSet<>();

    @BeforeAll
    void declareTopologyAndWaitForTheListener() {
        rabbitAdmin.initialize();
        await(() -> consumerCount() > 0);
    }

    @AfterEach
    void deleteTestData() {
        if (!companyIds.isEmpty()) {
            jdbcTemplate.update("DELETE FROM forecast_cheques WHERE company_id = ANY (?)",
                    (Object) companyIds.toArray(UUID[]::new));
        }
        if (!eventIds.isEmpty()) {
            jdbcTemplate.update("DELETE FROM processed_events WHERE event_id = ANY (?)",
                    (Object) eventIds.toArray(UUID[]::new));
        }
        companyIds.clear();
        eventIds.clear();
        // Every test leaves the queues empty; anything left would leak into the next test.
        purge();
    }

    @AfterAll
    void deleteTopology() {
        listenerRegistry.stop();
        rabbitAdmin.deleteQueue(QUEUE);
        rabbitAdmin.deleteQueue(DLQ);
        rabbitAdmin.deleteExchange(EXCHANGE);
        rabbitAdmin.deleteExchange(DLX);
    }

    @Test
    void shouldProjectIssuedUpdatedAndCancelledCheques() {
        UUID companyId = company();
        UUID chequeId = UUID.randomUUID();

        ChequeMessages issued = event("ChequeIssued", companyId, chequeId, 1);
        publishAndAwait(issued);

        Map<String, Object> row = projection(chequeId);
        assertThat(row.get("company_id")).isEqualTo(companyId);
        assertThat(row.get("bank_account_id")).isEqualTo(UUID.fromString("5b0f4d3c-1a2b-4c5d-8e9f-0a1b2c3d4e5f"));
        assertThat(row.get("cheque_number")).isEqualTo("000123");
        assertThat(row.get("category")).isEqualTo("SUPPLIER_PAYMENT");
        assertThat((BigDecimal) row.get("amount")).isEqualByComparingTo("1500.00");
        assertThat(row.get("issue_date")).isEqualTo(Date.valueOf("2026-10-15"));
        assertThat(row.get("expected_clearing_date")).isEqualTo(Date.valueOf("2026-10-22"));
        assertThat(row.get("status")).isEqualTo("ISSUED");
        assertThat(row.get("source_version")).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT source_event_created_at FROM forecast_cheques WHERE cheque_id = ?",
                OffsetDateTime.class, chequeId).toInstant()).isEqualTo(ChequeMessages.CREATED_AT.toInstant());
        assertThat(row.get("last_event_id")).isEqualTo(issued.messageId);
        assertThat(outcome(issued)).isEqualTo("APPLIED");

        ChequeMessages updated = event("ChequeUpdated", companyId, chequeId, 2)
                .field("amount", "2750.50")
                .field("category", "TAX")
                .field("expectedClearingDate", "2026-10-29");
        publishAndAwait(updated);

        row = projection(chequeId);
        assertThat((BigDecimal) row.get("amount")).isEqualByComparingTo("2750.50");
        assertThat(row.get("category")).isEqualTo("TAX");
        assertThat(row.get("expected_clearing_date")).isEqualTo(Date.valueOf("2026-10-29"));
        assertThat(row.get("source_version")).isEqualTo(2);
        assertThat(row.get("last_event_id")).isEqualTo(updated.messageId);

        ChequeMessages cancelled = event("ChequeCancelled", companyId, chequeId, 3)
                .field("amount", "2750.50")
                .field("category", "TAX")
                .field("expectedClearingDate", "2026-10-29");
        publishAndAwait(cancelled);

        row = projection(chequeId);
        assertThat(row.get("status")).isEqualTo("CANCELLED");
        assertThat(row.get("source_version")).isEqualTo(3);
        assertThat(outcome(cancelled)).isEqualTo("APPLIED");
        assertThat(deadLetters()).isEmpty();
    }

    @Test
    void shouldProjectABouncedChequeSoItLeavesTheForecast() {
        UUID companyId = company();
        UUID chequeId = UUID.randomUUID();
        publishAndAwait(event("ChequeIssued", companyId, chequeId, 1));

        ChequeMessages bounced = event("ChequeBounced", companyId, chequeId, 2).field("bouncedOn", "2026-10-23");
        publishAndAwait(bounced);

        Map<String, Object> row = projection(chequeId);
        assertThat(row.get("status")).isEqualTo("BOUNCED");
        assertThat(row.get("source_version")).isEqualTo(2);
        assertThat(outcome(bounced)).isEqualTo("APPLIED");
        assertThat(deadLetters()).isEmpty();
    }

    @Test
    void shouldApplyARedeliveredMessageIdOnceAndAcknowledgeIt() {
        UUID companyId = company();
        UUID chequeId = UUID.randomUUID();
        ChequeMessages issued = event("ChequeIssued", companyId, chequeId, 1);

        publishAndAwait(issued);
        Object projectedAt = projection(chequeId).get("projected_at");
        publish(issued);
        publish(issued);
        awaitQueueDrained();

        assertThat(processedCount(issued)).isEqualTo(1);
        assertThat(outcome(issued)).isEqualTo("APPLIED");
        assertThat(projection(chequeId).get("projected_at")).isEqualTo(projectedAt);
        assertThat(deadLetters()).isEmpty();
        assertAllAcknowledged();
    }

    @Test
    void shouldIgnoreOlderVersionsArrivingLate() {
        UUID companyId = company();
        UUID chequeId = UUID.randomUUID();
        ChequeMessages cancelled = event("ChequeCancelled", companyId, chequeId, 3);
        ChequeMessages issued = event("ChequeIssued", companyId, chequeId, 1).field("amount", "10.00");
        ChequeMessages updated = event("ChequeUpdated", companyId, chequeId, 2).field("amount", "20.00");
        ChequeMessages sameVersion = event("ChequeUpdated", companyId, chequeId, 3).field("amount", "30.00");

        publishAndAwait(cancelled);
        publishAndAwait(issued);
        publishAndAwait(updated);
        // A different event carrying the version already applied changes nothing either.
        publishAndAwait(sameVersion);

        Map<String, Object> row = projection(chequeId);
        assertThat(row.get("status")).isEqualTo("CANCELLED");
        assertThat(row.get("source_version")).isEqualTo(3);
        assertThat((BigDecimal) row.get("amount")).isEqualByComparingTo("1500.00");
        assertThat(row.get("last_event_id")).isEqualTo(cancelled.messageId);
        assertThat(outcome(issued)).isEqualTo("STALE");
        assertThat(outcome(updated)).isEqualTo("STALE");
        assertThat(outcome(sameVersion)).isEqualTo("STALE");
        assertAllAcknowledged();
    }

    @Test
    void shouldDeadLetterInvalidMessagesWithoutApplyingThem() {
        UUID companyId = company();
        UUID chequeId = UUID.randomUUID();
        List<ChequeMessages> invalid = List.of(
                event("ChequeIssued", companyId, chequeId, 1).header("schemaVersion", 2),
                event("ChequeCleared", companyId, chequeId, 1),
                event("ChequeIssued", companyId, chequeId, 1).body("{\"chequeId\": "),
                event("ChequeIssued", companyId, chequeId, 1).field("amount", "-5.00"),
                event("ChequeIssued", companyId, chequeId, 1).field("companyId", UUID.randomUUID().toString()),
                event("ChequeIssued", companyId, chequeId, 1).header("aggregateId", UUID.randomUUID().toString()));

        invalid.forEach(this::publish);
        List<Message> deadLetters = awaitDeadLetters(invalid.size());

        assertThat(deadLetters).extracting(m -> m.getMessageProperties().getMessageId())
                .containsExactlyInAnyOrderElementsOf(invalid.stream().map(m -> m.messageId.toString()).toList());
        assertThat(deadLetters).allSatisfy(m ->
                assertThat(m.getMessageProperties().<Object>getHeader("x-first-death-reason")).isEqualTo("rejected"));
        assertThat(findProjection(chequeId)).isEmpty();
        invalid.forEach(m -> assertThat(processedCount(m)).isZero());
        assertAllAcknowledged();
    }

    @Test
    void shouldDeadLetterAnEventThatMovesAChequeToAnotherCompany() {
        UUID companyId = company();
        UUID otherCompanyId = company();
        UUID chequeId = UUID.randomUUID();
        publishAndAwait(event("ChequeIssued", companyId, chequeId, 1));

        ChequeMessages moved = event("ChequeUpdated", otherCompanyId, chequeId, 2).field("amount", "99.00");
        publish(moved);
        List<Message> deadLetters = awaitDeadLetters(1);

        assertThat(deadLetters.getFirst().getMessageProperties().getMessageId()).isEqualTo(moved.messageId.toString());
        Map<String, Object> row = projection(chequeId);
        assertThat(row.get("company_id")).isEqualTo(companyId);
        assertThat(row.get("source_version")).isEqualTo(1);
        assertThat(processedCount(moved)).isZero();
    }

    @Test
    void shouldRollBackAFailedAttemptAndApplyTheRetry() {
        doThrow(new TransientDataAccessResourceException("connection lost"))
                .doCallRealMethod()
                .when(store).recordOutcome(any(), anyString());
        UUID chequeId = UUID.randomUUID();
        ChequeMessages issued = event("ChequeIssued", company(), chequeId, 1);

        publishAndAwait(issued);

        // Had the first attempt's processed_events row survived, the retry would be a DUPLICATE.
        assertThat(outcome(issued)).isEqualTo("APPLIED");
        assertThat(processedCount(issued)).isEqualTo(1);
        assertThat(projection(chequeId).get("source_version")).isEqualTo(1);
        verify(store, times(2)).upsert(any());
        assertAllAcknowledged();
    }

    @Test
    void shouldDeadLetterAfterBoundedRetriesAndLeaveNoPartialChange() {
        doThrow(new TransientDataAccessResourceException("connection lost"))
                .when(store).recordOutcome(any(), anyString());
        UUID chequeId = UUID.randomUUID();
        ChequeMessages issued = event("ChequeIssued", company(), chequeId, 1);

        publish(issued);
        List<Message> deadLetters = awaitDeadLetters(1);

        assertThat(deadLetters.getFirst().getMessageProperties().getMessageId()).isEqualTo(issued.messageId.toString());
        verify(store, times(MAX_ATTEMPTS)).upsert(any());
        assertThat(findProjection(chequeId)).isEmpty();
        assertThat(processedCount(issued)).isZero();
        assertAllAcknowledged();
    }

    @Test
    void shouldRecoverAfterTransientDatabaseFailures() {
        doThrow(new TransientDataAccessResourceException("connection lost"))
                .doThrow(new TransientDataAccessResourceException("connection lost"))
                .doCallRealMethod()
                .when(store).upsert(any());
        UUID chequeId = UUID.randomUUID();
        ChequeMessages issued = event("ChequeIssued", company(), chequeId, 1);

        publishAndAwait(issued);

        assertThat(outcome(issued)).isEqualTo("APPLIED");
        verify(store, times(3)).upsert(any());
        assertThat(deadLetters()).isEmpty();
    }

    @Test
    void shouldApplyAMessageRedeliveredAfterAConsumerDiedBeforeAcknowledging() {
        UUID chequeId = UUID.randomUUID();
        ChequeMessages issued = event("ChequeIssued", company(), chequeId, 1);
        MessageListenerContainer container = listenerRegistry.getListenerContainer("chequeEvents");

        container.stop();
        try {
            publish(issued);
            // Another consumer takes the message and goes away without acknowledging it.
            await(this::takeAndDie);
        } finally {
            container.start();
        }
        await(() -> outcome(issued) != null);

        assertThat(outcome(issued)).isEqualTo("APPLIED");
        assertThat(projection(chequeId).get("source_version")).isEqualTo(1);
        assertAllAcknowledged();
    }

    @Test
    void shouldAcknowledgeAnEventCommittedBeforeItsAcknowledgementWasLost() throws IOException {
        UUID chequeId = UUID.randomUUID();
        ChequeMessages issued = event("ChequeIssued", company(), chequeId, 1);
        Channel brokenChannel = mock(Channel.class);
        doThrow(new IOException("connection reset")).when(brokenChannel).basicAck(anyLong(), anyBoolean());

        assertThatThrownBy(() -> listener.onMessage(issued.message(), brokenChannel)).isInstanceOf(IOException.class);
        assertThat(outcome(issued)).isEqualTo("APPLIED");
        Object projectedAt = projection(chequeId).get("projected_at");

        // The broker delivers the unacknowledged message again.
        publish(issued);
        awaitQueueDrained();

        assertThat(processedCount(issued)).isEqualTo(1);
        assertThat(projection(chequeId).get("projected_at")).isEqualTo(projectedAt);
        assertThat(deadLetters()).isEmpty();
        assertAllAcknowledged();
        verify(brokenChannel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    }

    @Test
    void shouldApplyTheSameEventOnceWhenProcessedConcurrently() throws Exception {
        ChequeEvent event = parser.parse(event("ChequeIssued", company(), UUID.randomUUID(), 1).message());

        List<ChequeEventProcessor.Outcome> outcomes = concurrently(8, i -> event);

        assertThat(outcomes).containsOnlyOnce(ChequeEventProcessor.Outcome.APPLIED);
        assertThat(outcomes).filteredOn(o -> o == ChequeEventProcessor.Outcome.DUPLICATE).hasSize(7);
        assertThat(projection(event.chequeId()).get("source_version")).isEqualTo(1);
    }

    @Test
    void shouldKeepTheHighestVersionWhenVersionsArriveConcurrently() throws Exception {
        UUID companyId = company();
        UUID chequeId = UUID.randomUUID();
        List<ChequeEvent> events = new ArrayList<>();
        for (int version = 1; version <= 8; version++) {
            events.add(parser.parse(event("ChequeUpdated", companyId, chequeId, version)
                    .field("amount", version + "00.00").message()));
        }

        List<ChequeEventProcessor.Outcome> outcomes = concurrently(8, events::get);

        assertThat(outcomes).doesNotContain(ChequeEventProcessor.Outcome.DUPLICATE);
        Map<String, Object> row = projection(chequeId);
        assertThat(row.get("source_version")).isEqualTo(8);
        assertThat((BigDecimal) row.get("amount")).isEqualByComparingTo("800.00");
        assertThat(row.get("last_event_id")).isEqualTo(events.get(7).messageId());
        events.forEach(e -> assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM processed_events WHERE event_id = ?", Integer.class, e.messageId())).isEqualTo(1));
    }

    @Test
    void shouldNeverChangeSavedForecastRunsOrTheirChequeSnapshots() {
        UUID companyId = company();
        UUID chequeId = UUID.randomUUID();
        UUID runId = insertSavedRunWithCheque(companyId, chequeId);
        try {
            String before = savedRunsFingerprint();
            Map<String, Object> snapshotBefore = runCheque(runId, chequeId);

            publishAndAwait(event("ChequeIssued", companyId, chequeId, 1));
            publishAndAwait(event("ChequeUpdated", companyId, chequeId, 2).field("amount", "9999.99"));
            publishAndAwait(event("ChequeCancelled", companyId, chequeId, 3));

            assertThat(projection(chequeId).get("status")).isEqualTo("CANCELLED");
            assertThat(savedRunsFingerprint()).isEqualTo(before);
            assertThat(runCheque(runId, chequeId)).isEqualTo(snapshotBefore);
            assertThat(runCheque(runId, chequeId).get("status_at_run")).isEqualTo("ISSUED");
        } finally {
            deleteSavedRuns(companyId);
        }
    }

    @Test
    void shouldNotStoreOrLogSensitiveFields(CapturedOutput output) {
        UUID chequeId = UUID.randomUUID();
        UUID companyId = company();
        ChequeMessages valid = event("ChequeIssued", companyId, chequeId, 1)
                .field("payeeName", "Secret Payee Holdings")
                .field("notes", "private settlement note")
                .field("chequeNumber", "55501234")
                .field("amount", "98765.43");
        ChequeMessages invalid = event("ChequeUpdated", companyId, chequeId, 2)
                .field("payeeName", "Secret Payee Holdings")
                .field("chequeNumber", "55501234")
                .field("amount", "98765.431");

        publishAndAwait(valid);
        publish(invalid);
        awaitDeadLetters(1);

        assertThat(output.getOut() + output.getErr())
                .contains(valid.messageId.toString(), invalid.messageId.toString())
                .doesNotContain("Secret Payee", "private settlement", "55501234", "98765.43");
        assertThat(jdbcTemplate.queryForList("""
                        SELECT column_name FROM information_schema.columns
                        WHERE table_name IN ('forecast_cheques', 'processed_events')
                        """, String.class))
                .noneMatch(column -> column.contains("payee") || column.contains("note"));
        String summary = jdbcTemplate.queryForObject(
                "SELECT result_summary::text FROM processed_events WHERE event_id = ?", String.class, valid.messageId);
        assertThat(summary).doesNotContain("98765", "55501234", "Secret");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT array_to_string(array(SELECT jsonb_object_keys(result_summary) ORDER BY 1), ',') "
                        + "FROM processed_events WHERE event_id = ?", String.class, valid.messageId))
                .isEqualTo("chequeId,outcome,sourceVersion");
    }

    @Test
    void shouldDeadLetterAMessageThatKeepsComingBackUnacknowledged() {
        ChequeMessages issued = event("ChequeIssued", company(), UUID.randomUUID(), 1);
        MessageListenerContainer container = listenerRegistry.getListenerContainer("chequeEvents");

        container.stop();
        List<Message> deadLetters;
        try {
            publish(issued);
            // A consumer that crashes on this message every time: the broker's delivery limit stops the loop.
            for (int i = 0; i <= DELIVERY_LIMIT; i++) {
                await(this::takeAndDie);
            }
            deadLetters = awaitDeadLetters(1);
        } finally {
            container.start();
        }

        assertThat(deadLetters.getFirst().getMessageProperties().getMessageId()).isEqualTo(issued.messageId.toString());
        assertThat(deadLetters.getFirst().getMessageProperties().<Object>getHeader("x-first-death-reason"))
                .isEqualTo("delivery_limit");
        assertThat(processedCount(issued)).isZero();
    }

    // Helpers

    private UUID company() {
        UUID companyId = UUID.randomUUID();
        companyIds.add(companyId);
        return companyId;
    }

    private ChequeMessages event(String eventType, UUID companyId, UUID chequeId, int version) {
        ChequeMessages message = ChequeMessages.event(eventType, companyId, chequeId, version);
        eventIds.add(message.messageId);
        return message;
    }

    private void publish(ChequeMessages message) {
        rabbitTemplate.send(EXCHANGE, message.routingKey(), message.message());
    }

    private void publishAndAwait(ChequeMessages message) {
        publish(message);
        await(() -> outcome(message) != null);
    }

    // A marker event on a fresh cheque; one consumer handles the queue in order, so once the marker
    // is processed, everything published before it has been handled.
    private void awaitQueueDrained() {
        publishAndAwait(event("ChequeIssued", company(), UUID.randomUUID(), 1));
    }

    private String outcome(ChequeMessages message) {
        return jdbcTemplate.queryForList("SELECT result_summary ->> 'outcome' FROM processed_events WHERE event_id = ?",
                String.class, message.messageId).stream().findFirst().orElse(null);
    }

    private int processedCount(ChequeMessages message) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM processed_events WHERE event_id = ?",
                Integer.class, message.messageId);
    }

    private Map<String, Object> projection(UUID chequeId) {
        return findProjection(chequeId).orElseThrow(() -> new AssertionError("Cheque not projected"));
    }

    private Optional<Map<String, Object>> findProjection(UUID chequeId) {
        return jdbcTemplate.queryForList("SELECT * FROM forecast_cheques WHERE cheque_id = ?", chequeId)
                .stream().findFirst();
    }

    private List<Message> deadLetters() {
        List<Message> messages = new ArrayList<>();
        Message message;
        while ((message = rabbitTemplate.receive(DLQ)) != null) {
            messages.add(message);
        }
        return messages;
    }

    private List<Message> awaitDeadLetters(int count) {
        List<Message> messages = new ArrayList<>();
        await(() -> {
            messages.addAll(deadLetters());
            return messages.size() >= count;
        });
        return messages;
    }

    // A consumer on its own connection takes the next message and dies before acknowledging it,
    // so the broker returns the message to the queue. Returns false if the queue was empty.
    private boolean takeAndDie() {
        try (Connection connection = connectionFactory.getRabbitConnectionFactory().newConnection()) {
            return connection.createChannel().basicGet(QUEUE, false) != null;
        } catch (IOException | TimeoutException e) {
            throw new IllegalStateException(e);
        }
    }

    private void assertAllAcknowledged() {
        MessageListenerContainer container = listenerRegistry.getListenerContainer("chequeEvents");
        // Stopping the consumer returns any unacknowledged message to the queue.
        container.stop();
        try {
            assertThat(messageCount(QUEUE)).isZero();
        } finally {
            container.start();
        }
        await(() -> consumerCount() > 0);
    }

    private void purge() {
        rabbitAdmin.purgeQueue(DLQ, false);
        rabbitAdmin.purgeQueue(QUEUE, false);
    }

    private int messageCount(String queue) {
        return ((Number) rabbitAdmin.getQueueProperties(queue).get(RabbitAdmin.QUEUE_MESSAGE_COUNT)).intValue();
    }

    private int consumerCount() {
        var properties = rabbitAdmin.getQueueProperties(QUEUE);
        return properties == null ? 0 : ((Number) properties.get(RabbitAdmin.QUEUE_CONSUMER_COUNT)).intValue();
    }

    private List<ChequeEventProcessor.Outcome> concurrently(int threads, IntFunction<ChequeEvent> events) throws Exception {
        CyclicBarrier start = new CyclicBarrier(threads);
        List<ChequeEventProcessor.Outcome> outcomes = new CopyOnWriteArrayList<>();
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                ChequeEvent event = events.apply(i);
                Callable<Void> task = () -> {
                    start.await(10, TimeUnit.SECONDS);
                    outcomes.add(processor.process(event));
                    return null;
                };
                futures.add(executor.submit(task));
            }
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }
        return outcomes;
    }

    private UUID insertSavedRunWithCheque(UUID companyId, UUID chequeId) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            UUID configId = jdbcTemplate.queryForObject("""
                            INSERT INTO forecast_model_configs (company_id, version, is_active, horizon_days, method_code,
                                                                created_by)
                            VALUES (?, 1, true, 84, 'HISTORICAL_BANK_CASH_FLOW_PATTERN', ?)
                            RETURNING id
                            """,
                    UUID.class, companyId, UUID.randomUUID());
            UUID runId = jdbcTemplate.queryForObject("""
                            INSERT INTO forecast_runs (company_id, config_id, bank_account_id, run_status, trigger_type,
                                as_of_at, horizon_start_date, horizon_end_date, opening_balance_amount,
                                opening_balance_as_of, balance_stale, inflow_estimate_status, model_version,
                                config_snapshot, input_snapshot_hash, completed_at)
                            VALUES (?, ?, ?, 'SUCCEEDED', 'MANUAL', now(), DATE '2026-10-15', DATE '2027-01-06',
                                1000.00, now(), false, 'ESTIMATED', '1:WEEKDAY_AVERAGE', '{}', ?, now())
                            RETURNING id
                            """,
                    UUID.class, companyId, configId, UUID.randomUUID(), "c".repeat(64));
            jdbcTemplate.update("""
                            INSERT INTO forecast_run_cheques (forecast_run_id, cheque_id, cheque_number, amount,
                                                              expected_clearing_date, status_at_run, source_version)
                            VALUES (?, ?, '000123', 1500.00, ?, 'ISSUED', 1)
                            """,
                    runId, chequeId, LocalDate.of(2026, 10, 22));
            return runId;
        });
    }

    private Map<String, Object> runCheque(UUID runId, UUID chequeId) {
        return new HashMap<>(jdbcTemplate.queryForMap(
                "SELECT * FROM forecast_run_cheques WHERE forecast_run_id = ? AND cheque_id = ?", runId, chequeId));
    }

    private String savedRunsFingerprint() {
        return jdbcTemplate.queryForObject("""
                SELECT (SELECT count(*) || ':' || coalesce(md5(string_agg(t::text, ',' ORDER BY t::text)), '')
                        FROM forecast_runs t)
                    || ' ' || (SELECT count(*) || ':' || coalesce(md5(string_agg(t::text, ',' ORDER BY t::text)), '')
                        FROM forecast_daily_results t)
                    || ' ' || (SELECT count(*) || ':' || coalesce(md5(string_agg(t::text, ',' ORDER BY t::text)), '')
                        FROM forecast_run_cheques t)
                """, String.class);
    }

    // Saved runs are immutable, so the committed test data can only be removed with the
    // database's trigger bypass, available to the local superuser. Test cleanup only.
    private void deleteSavedRuns(UUID companyId) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbcTemplate.execute("SET LOCAL session_replication_role = replica");
            jdbcTemplate.update("""
                    DELETE FROM forecast_run_cheques
                    WHERE forecast_run_id IN (SELECT id FROM forecast_runs WHERE company_id = ?)
                    """, companyId);
            jdbcTemplate.update("DELETE FROM forecast_runs WHERE company_id = ?", companyId);
            jdbcTemplate.update("DELETE FROM forecast_model_configs WHERE company_id = ?", companyId);
        });
    }

    private static void await(BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                fail("Condition not met within 15 seconds");
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("Interrupted while waiting");
            }
        }
    }
}
