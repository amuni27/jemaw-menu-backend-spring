package com.agafari.com.billing.stripe;

import com.stripe.exception.ApiConnectionException;
import com.stripe.exception.StripeException;
import com.stripe.net.HttpClient;
import com.stripe.net.HttpHeaders;
import com.stripe.net.StripeRequest;
import com.stripe.net.StripeResponse;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AGF SUB-R5-02 (tests only): the Stripe SDK's HTTP layer replaced by routes, so the real adapter and the real SDK
 * (34.1.0, API {@code 2026-10-28.endive}) run with <b>no network call</b>. Every request is recorded: method, path,
 * parameters, idempotency key and the API version header the SDK sends. Responses are synthetic ({@link StripeFixtures}).
 */
public final class StripeStub extends HttpClient {

    public record Call(String method, String path, Map<String, Object> params, String idempotencyKey, String stripeVersion) {
    }

    @FunctionalInterface
    public interface Responder {
        StripeResponse respond(Call call) throws StripeException;
    }

    private final List<Call> calls = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, Responder> routes = new ConcurrentHashMap<>();

    public void reset() {
        calls.clear();
        routes.clear();
    }

    public StripeStub on(String method, String path, Responder responder) {
        routes.put(method + " " + path, responder);
        return this;
    }

    public List<Call> calls() {
        synchronized (calls) {
            return List.copyOf(calls);
        }
    }

    public List<Call> calls(String method, String path) {
        return calls().stream().filter(c -> c.method().equals(method) && c.path().equals(path)).toList();
    }

    @Override
    public StripeResponse request(StripeRequest request) throws StripeException {
        Call call = new Call(request.method().name(), request.url().getPath(), request.params() == null ? Map.of() : request.params(),
                request.headers().firstValue("Idempotency-Key").orElse(null), request.headers().firstValue("Stripe-Version").orElse(null));
        calls.add(call);
        Responder r = routes.get(call.method() + " " + call.path());
        if (r == null) return error(404, "invalid_request_error", "resource_missing");
        return r.respond(call);
    }

    public static StripeResponse ok(String json) {
        return new StripeResponse(200, HttpHeaders.of(Map.of("request-id", List.of("req_synthetic"))), json);
    }

    public static StripeResponse error(int status, String type, String code) {
        return new StripeResponse(status, HttpHeaders.of(Map.of("request-id", List.of("req_synthetic"))),
                "{\"error\":{\"type\":\"" + type + "\",\"code\":\"" + code + "\",\"message\":\"synthetic " + code + "\"}}");
    }

    public static StripeResponse timeout() throws StripeException {
        throw new ApiConnectionException("synthetic connection timeout");
    }
}
