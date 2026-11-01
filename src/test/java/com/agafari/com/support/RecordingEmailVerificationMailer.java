package com.agafari.com.support;

import com.agafari.com.security.EmailVerificationMailer;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * AGF-S2-22 test mailer: records verification links instead of sending them. Operational by default, so verification
 * is available in tests (the publish gate still needs its flag); tests can switch it off or make deliveries fail.
 */
public class RecordingEmailVerificationMailer implements EmailVerificationMailer {

    private final List<Map.Entry<String, String>> sent = new CopyOnWriteArrayList<>();
    private volatile boolean operational = true;
    private volatile boolean failing = false;

    @Override
    public void sendVerificationLink(String email, String verifyUrl) {
        if (failing) throw new IllegalStateException("simulated provider outage");
        sent.add(Map.entry(email, verifyUrl));
    }

    @Override
    public boolean operational() {
        return operational;
    }

    public List<Map.Entry<String, String>> sent() {
        return sent;
    }

    public void setOperational(boolean operational) {
        this.operational = operational;
    }

    public void setFailing(boolean failing) {
        this.failing = failing;
    }

    public void reset() {
        sent.clear();
        operational = true;
        failing = false;
    }
}
