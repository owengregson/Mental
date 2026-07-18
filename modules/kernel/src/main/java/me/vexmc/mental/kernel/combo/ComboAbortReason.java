package me.vexmc.mental.kernel.combo;

/**
 * Why a DEVELOPING (pre-activation) chain died without ever activating.
 * Distinct from {@link ComboEndReason}: an active combo's end vocabulary is
 * frozen by the public api (gen-3 §5.5), while developing chains can die by
 * attacker switch — a cause an active end never reports. GROUNDED/BLOWOUT are
 * deliberately absent: both tracker guards are active-only, so a developing
 * chain can never reach them.
 */
public enum ComboAbortReason {
    EXPIRED,
    SWITCHED,
    RETALIATION,
    RETIRED,
    DISABLED
}
