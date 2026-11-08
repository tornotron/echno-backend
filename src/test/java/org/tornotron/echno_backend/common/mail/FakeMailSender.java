package org.tornotron.echno_backend.common.mail;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** Keeps what it was given and fails when told to, for tests of whatever sends through it. */
public class FakeMailSender implements MailSender {

    private final List<MailMessage> sent = new ArrayList<>();
    private final Deque<MailSendException> failures = new ArrayDeque<>();
    private int attempts;

    @Override
    public synchronized String send(MailMessage message) {
        attempts++;
        MailSendException failure = failures.poll();
        if (failure != null) {
            throw failure;
        }
        sent.add(message);
        return "fake-" + sent.size();
    }

    /** The next sends fail with these, in order. */
    public synchronized void failNext(MailSendException... next) {
        failures.addAll(List.of(next));
    }

    public synchronized List<MailMessage> sent() {
        return List.copyOf(sent);
    }

    public synchronized int attempts() {
        return attempts;
    }

    public synchronized void clear() {
        sent.clear();
        failures.clear();
        attempts = 0;
    }
}
