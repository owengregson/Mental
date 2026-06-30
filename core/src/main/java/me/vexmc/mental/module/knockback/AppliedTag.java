package me.vexmc.mental.module.knockback;

import org.jetbrains.annotations.NotNull;

/**
 * What {@link KnockbackPipeline}'s HIGH velocity handler applied, carried to its
 * MONITOR ledger-record handler for the same event. {@code grounded} is the
 * launch state captured at SUBMIT — the era's pre-move friction state, which the
 * live flag has already moved past by the time the record runs. Carries no
 * timestamp: the {@link AppliedTagStore} pairs the two handlers structurally,
 * not on a wall clock.
 */
record AppliedTag(@NotNull KnockbackPipeline.Cause cause, boolean grounded) {}
