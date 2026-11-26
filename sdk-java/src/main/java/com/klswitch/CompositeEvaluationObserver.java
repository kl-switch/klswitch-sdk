package com.klswitch;

import java.util.List;

/** Fans out to several observers, in order. */
final class CompositeEvaluationObserver implements EvaluationObserver {

    private final List<EvaluationObserver> observers;

    CompositeEvaluationObserver(List<EvaluationObserver> observers) {
        this.observers = List.copyOf(observers);
    }

    @Override
    public void onEvaluated(Context ctx) {
        for (EvaluationObserver o : observers) {
            o.onEvaluated(ctx);
        }
    }
}
