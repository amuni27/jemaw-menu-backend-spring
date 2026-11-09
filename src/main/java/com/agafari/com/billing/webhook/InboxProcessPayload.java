package com.agafari.com.billing.webhook;

/** AGF SUB-R4-07: the {@code inbox.process} job's payload. Its handler arrives with SUB-R4-10; until then the job waits. */
public record InboxProcessPayload(String providerEventId) {
}
