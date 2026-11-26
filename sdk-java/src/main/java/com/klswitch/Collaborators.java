package com.klswitch;

/**
 * What the client and the observers need from their collaborators — and nothing
 * more.
 *
 * <p>The client used to hold {@code PollingManager}, {@code AnalyticsReporter}
 * and {@code ManifestReporter} by their concrete types, which meant none of them
 * could be substituted in a test without the real thing: a real HTTP transport, a
 * real scheduler, a real route walk.
 *
 * <p>These interfaces are deliberately narrow. A single fat {@code Reporter} type
 * would force a fake to implement batching, flushing and single-flight logic that
 * nothing here asks for.
 *
 * <p>They are nested in one file because they describe one idea — the seam
 * between the client and the three things it drives — and splitting four
 * three-line interfaces across four files would say less, not more.
 */
public final class Collaborators {

    private Collaborators() {}

    /** The recording half of analytics — what the observer needs. */
    public interface AnalyticsRecorder {
        void record(AnalyticsEvent event);
    }

    /**
     * The lifecycle half of analytics — what the client needs.
     *
     * <p>Split from {@link AnalyticsRecorder} on purpose: the observer records
     * and never closes, the client closes and never records. One interface
     * carrying both would hand each of them a method it has no business calling.
     */
    public interface AnalyticsControl {
        AnalyticsStatus status();

        void close();
    }

    /** What the client and the re-check observer need from manifest reporting. */
    public interface ManifestControl {
        boolean report();

        ManifestStatus status();

        void stop();

        void close();
    }

    /** What the client needs from the polling loop. */
    public interface PollingControl {
        void start(long intervalMs);

        void pollOnce();

        PollingManager.PollingStatus status();

        void close();
    }
}
