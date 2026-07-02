package me.vexmc.mental.platform;

/** Cancellation handle for a repeating task, safe to call from any thread. */
public interface TaskHandle {

    void cancel();

    boolean cancelled();
}
