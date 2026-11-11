package com.agafari.com.billing.webhook;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * AGF SUB-R5-02 (PO D5): the 256 KiB webhook body limit, by declared length and by actually streamed size, before any
 * signature check or storage. (The HTTP-level 413 with nothing stored is in {@code StripeCheckoutFlowTest}.)
 */
class WebhookBodyLimitTest {

    private static final int LIMIT = WebhookController.MAX_BODY_BYTES;

    private static HttpServletRequest request(long containerLength, String header, int actualBytes) throws IOException {
        HttpServletRequest r = mock(HttpServletRequest.class);
        when(r.getContentLengthLong()).thenReturn(containerLength);
        when(r.getHeader("Content-Length")).thenReturn(header);
        ByteArrayInputStream in = new ByteArrayInputStream(new byte[actualBytes]);
        when(r.getInputStream()).thenReturn(new ServletInputStream() {
            @Override public int read() { return in.read(); }
            @Override public int read(byte[] b, int off, int len) { return in.read(b, off, len); }
            @Override public boolean isFinished() { return in.available() == 0; }
            @Override public boolean isReady() { return true; }
            @Override public void setReadListener(ReadListener l) { }
        });
        return r;
    }

    @Test
    void theLimitIsExactly256KiB() {
        assertThat(LIMIT).isEqualTo(262_144);
    }

    @Test
    void aBodyAtTheLimitIsReadAndOneByteMoreIsRefused() throws IOException {
        assertThat(WebhookController.boundedBody(request(LIMIT, String.valueOf(LIMIT), LIMIT))).hasSize(LIMIT);
        assertThat(WebhookController.boundedBody(request(LIMIT + 1, String.valueOf(LIMIT + 1), LIMIT + 1))).isNull();
    }

    @Test
    void anOversizeDeclaredLengthIsRefusedWithoutReadingTheBody() throws IOException {
        HttpServletRequest declaredByHeader = request(-1, String.valueOf(LIMIT + 1), 10);
        assertThat(WebhookController.boundedBody(declaredByHeader)).isNull();
        org.mockito.Mockito.verify(declaredByHeader, org.mockito.Mockito.never()).getInputStream();
        assertThat(WebhookController.boundedBody(request(LIMIT + 1, null, 10))).as("container's length").isNull();
        assertThat(WebhookController.boundedBody(request(-1, "not-a-number", 10))).as("a malformed length").isNull();
    }

    @Test
    void anOversizeStreamWithAnUnderstatedOrMissingLengthIsRefused() throws IOException {
        assertThat(WebhookController.boundedBody(request(-1, null, LIMIT + 1))).as("chunked, no length").isNull();
        assertThat(WebhookController.boundedBody(request(100, "100", 5 * LIMIT))).as("understated length").isNull();
        assertThat(WebhookController.boundedBody(request(-1, null, 0))).isEmpty();
    }
}
