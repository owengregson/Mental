package me.vexmc.mental.tester;

import org.jetbrains.annotations.NotNull;

/**
 * A test-body signal that the scenario cannot be honestly exercised on the
 * running server — not a failure and not a silent pass, but an explicit,
 * reasoned SKIP. Thrown (via {@link TestContext#skip}) when a mechanic the case
 * depends on is a harness limitation on this version band (e.g. a clientless
 * fake player's motion is not server-integrated on the pre-1.11 NMS, so a
 * knocked victim cannot physically fly and the flown endpoint cannot be
 * observed). The harness reports it as {@code [test] SKIP <name>: <reason>} and
 * excludes it from the failure set, so the reason stays loud in the suite log
 * while the run still passes — the substitute proof (velocity-level pins,
 * kernel-pinned trajectory math) is cited in the reason.
 */
public final class SuiteSkip extends RuntimeException {

    public SuiteSkip(@NotNull String reason) {
        super(reason);
    }
}
