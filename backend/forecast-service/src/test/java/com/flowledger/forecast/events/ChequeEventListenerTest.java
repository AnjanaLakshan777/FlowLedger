package com.flowledger.forecast.events;

import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.TransientDataAccessResourceException;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Acknowledgement and retry decisions of the listener, with the processor and channel mocked.
 */
class ChequeEventListenerTest {

    private static final long TAG = 42L;
    private static final ChequeEventProperties PROPERTIES = new ChequeEventProperties(true, "x", "cheque.*", "q",
            "dlx", "dlq", 4, Duration.ofMillis(500), Duration.ofSeconds(1), 5);

    private final ChequeEventProcessor processor = mock(ChequeEventProcessor.class);
    private final Channel channel = mock(Channel.class);
    private final List<Duration> sleeps = new ArrayList<>();
    private ChequeEventListener listener;

    @BeforeEach
    void setUp() {
        listener = new ChequeEventListener(new ChequeEventParser(JsonMapper.builder().build()), processor, PROPERTIES,
                sleeps::add);
    }

    @Test
    void shouldAcknowledgeAfterProcessing() throws IOException {
        when(processor.process(any())).thenReturn(ChequeEventProcessor.Outcome.APPLIED);

        listener.onMessage(validMessage(), channel);

        var order = inOrder(processor, channel);
        order.verify(processor).process(any());
        order.verify(channel).basicAck(TAG, false);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    }

    @Test
    void shouldAcknowledgeDuplicatesAndStaleEvents() throws IOException {
        when(processor.process(any()))
                .thenReturn(ChequeEventProcessor.Outcome.DUPLICATE)
                .thenReturn(ChequeEventProcessor.Outcome.STALE);

        listener.onMessage(validMessage(), channel);
        listener.onMessage(validMessage(), channel);

        verify(channel, times(2)).basicAck(TAG, false);
    }

    @Test
    void shouldDeadLetterInvalidMessagesWithoutProcessing() throws IOException {
        Message message = ChequeMessages.event("ChequeIssued", UUID.randomUUID(), UUID.randomUUID(), 1)
                .header("schemaVersion", 2).message();
        message.getMessageProperties().setDeliveryTag(TAG);

        listener.onMessage(message, channel);

        verify(channel).basicNack(TAG, false, false);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
        verifyNoInteractions(processor);
    }

    @Test
    void shouldDeadLetterTenantMismatchWithoutRetry() throws IOException {
        when(processor.process(any())).thenThrow(new InvalidChequeEventException("another company"));

        listener.onMessage(validMessage(), channel);

        verify(processor, times(1)).process(any());
        verify(channel).basicNack(TAG, false, false);
        assertThat(sleeps).isEmpty();
    }

    @Test
    void shouldNotRetryConstraintViolations() throws IOException {
        when(processor.process(any())).thenThrow(new DataIntegrityViolationException("check"));

        listener.onMessage(validMessage(), channel);

        verify(processor, times(1)).process(any());
        verify(channel).basicNack(TAG, false, false);
    }

    @Test
    void shouldRetryTransientFailuresWithCappedBackoffThenDeadLetter() throws IOException {
        when(processor.process(any())).thenThrow(new TransientDataAccessResourceException("database unavailable"));

        listener.onMessage(validMessage(), channel);

        verify(processor, times(4)).process(any());
        assertThat(sleeps).containsExactly(Duration.ofMillis(500), Duration.ofSeconds(1), Duration.ofSeconds(1));
        verify(channel).basicNack(TAG, false, false);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
    }

    @Test
    void shouldRecoverWhenARetrySucceeds() throws IOException {
        when(processor.process(any()))
                .thenThrow(new TransientDataAccessResourceException("database unavailable"))
                .thenReturn(ChequeEventProcessor.Outcome.APPLIED);

        listener.onMessage(validMessage(), channel);

        verify(processor, times(2)).process(any());
        verify(channel).basicAck(TAG, false);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    }

    @Test
    void shouldRequeueWhenInterruptedWhileWaitingToRetry() throws IOException {
        when(processor.process(any())).thenThrow(new TransientDataAccessResourceException("database unavailable"));
        listener = new ChequeEventListener(new ChequeEventParser(JsonMapper.builder().build()), processor, PROPERTIES,
                duration -> {
                    throw new InterruptedException();
                });

        try {
            listener.onMessage(validMessage(), channel);

            verify(channel).basicNack(TAG, false, true);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void shouldLeaveTheAcknowledgementFailureToTheBroker() throws IOException {
        when(processor.process(any())).thenReturn(ChequeEventProcessor.Outcome.APPLIED);
        doThrow(new IOException("channel closed")).when(channel).basicAck(TAG, false);

        assertThatThrownBy(() -> listener.onMessage(validMessage(), channel)).isInstanceOf(IOException.class);

        // Processed once, not retried; the broker redelivers the unacknowledged message.
        verify(processor, times(1)).process(any());
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    }

    private static Message validMessage() {
        Message message = ChequeMessages.event("ChequeIssued", UUID.randomUUID(), UUID.randomUUID(), 1).message();
        message.getMessageProperties().setDeliveryTag(TAG);
        return message;
    }
}
