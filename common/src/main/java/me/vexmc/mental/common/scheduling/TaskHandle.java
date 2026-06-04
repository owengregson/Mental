package me.vexmc.mental.common.scheduling;

/** Cancellation handle for a repeating task, safe to call from any thread. */
public interface TaskHandle {

    void cancel();

    boolean cancelled();
}
