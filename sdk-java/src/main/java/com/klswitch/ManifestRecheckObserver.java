package com.klswitch;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Pushes the route manifest once, on the first evaluation.
 *
 * <p>This cannot move to construction, which is where it belongs on the face of
 * it: routes are registered after the client exists, and the initial deferred
 * push can walk an empty route table. By the first request every route exists.
 * So the trigger stays where it was; what changed is that {@code evaluate()} no
 * longer knows about it. Cheap: {@code report()} hashes the route list and skips
 * the network when nothing has changed.
 */
final class ManifestRecheckObserver implements EvaluationObserver {

    private final Collaborators.ManifestControl reporter;
    private final AtomicBoolean done = new AtomicBoolean(false);

    ManifestRecheckObserver(Collaborators.ManifestControl reporter) {
        this.reporter = reporter;
    }

    @Override
    public void onEvaluated(Context ctx) {
        if (done.compareAndSet(false, true)) {
            Thread t = new Thread(reporter::report, "klswitch-manifest-recheck");
            t.setDaemon(true);
            t.start();
        }
    }
}
