package me.vexmc.mental.kernel.math;

import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;
import java.util.random.RandomGenerator;
import me.vexmc.mental.kernel.model.EntityState;
import me.vexmc.mental.kernel.model.KnockbackVector;
import me.vexmc.mental.kernel.profile.KnockbackProfile;
import me.vexmc.mental.kernel.profile.ModernKnockback;
import me.vexmc.mental.kernel.profile.PaceScaling;
import me.vexmc.mental.kernel.profile.ResistancePolicy;
import me.vexmc.mental.kernel.profile.VerticalMode;

/**
 * Pure knockback math, parameterized by a {@link KnockbackProfile}. With the
 * default {@code legacy-1.7} profile this is byte-identical to the 1.7.10
 * formula (which is itself byte-identical to 1.8.9 — only delivery changed
 * between those versions); the remaining knobs are the improved-knockback
 * fork vocabulary, each an era-exact no-op until a profile sets it.
 * Thread-agnostic: inputs are immutable {@link EntityState} captures, so the
 * same formula serves the main-thread event path and the netty fast path
 * bit-for-bit.
 *
 * <pre>
 *   1. LEGACY resistance rolls all-or-nothing first, exactly where the
 *      legacy {@code knockBack} early-returned; null means no knockback.
 *   2. Direction = (source − victim) on the horizontal plane, normalized;
 *      coincident positions get a tiny random direction (vanilla behavior).
 *      The source is a position: attacker for melee, angler for rods,
 *      shooter for projectiles — never the projectile itself.
 *   3. Melee only: the range-reduction taper shaves the horizontal base
 *      push for hits beyond its start distance (the MMC reach softening).
 *   4. base  = victimMotion × friction − direction × push,
 *      baseY = victimVy × frictionY + baseVertical  (vertical-mode ADD)
 *            = baseVertical                          (vertical-mode SET).
 *      The victim motion is the {@code VictimMotion} residual — the legacy
 *      server fields — which is what makes successive hits compound.
 *   5. Horizontal cap rescales (x, z) if configured; the vertical cap
 *      clamps the BASE y, so bonus levels push past it (sprint hits reach
 *      0.5 — the vanilla ordering).
 *   6. Melee only: sprint and Knockback enchant levels add the extra
 *      vector along the attacker's facing ({@code addVelocity} semantics —
 *      additive, never resistance-scaled). A freshly re-engaged sprint
 *      (w-tap) uses the {@code wtap-extra} pair when the profile enables it;
 *      enchant levels always use {@code extra}.
 *   7. Air multipliers scale the whole vector for airborne victims, the
 *      add offsets shift it sign-matched, and the vertical floor clamps —
 *      all identity at their defaults.
 *   8. SCALING resistance multiplies the horizontal result.
 *   9. Axes clamp to ±3.9, the legacy velocity-packet encoding limit.
 * </pre>
 *
 * <p><b>Speed-conformal knockback (pace scaling).</b> When the profile's
 * {@link PaceScaling} opts in, the {@link PaceScale} factor {@code s} multiplies
 * the FRESH horizontal knock — the base directional push (step 4's
 * {@code −direction × push}) and the sprint/wtap/enchant extras (step 6) — but
 * NOT the friction-carried residual (step 4's {@code victimMotion × friction})
 * and NEVER the vertical. This is the exact realization of the design's
 * "scale the horizontal knock" (§4.1) that makes the ledger interplay hold: the
 * MotionLedger records the scaled stamp, so a combo hit's residual already
 * carries the previous hits' scaling; re-scaling it would double-count. Scaling
 * only the fresh contribution keeps every hit's wire at {@code s ×} its
 * base-speed value, so a scaled hit-1 followed by a decayed combo hit-2 yields
 * <em>exactly</em> {@code s ×} the base-speed hit-2 (assumption A3). At
 * {@code s == 1.0} (mode off, base-speed play, or the projectile path) the
 * multiply is skipped entirely — byte-identical to the era stamp.</p>
 */
public final class KnockbackEngine {

    /** The legacy velocity packet clamped each axis to ±3.9 ({@code motion × 8000} shorts). */
    public static final double PACKET_CLAMP = 3.9;

    private KnockbackEngine() {}

    /**
     * A melee compute result plus the pace AND combo factors the engine actually
     * applied — the additive seam that lets a compute caller journal both without
     * a second computation or any engine mutation (D-6). {@code paceFactor} is
     * {@code 1.0} whenever pace was off/base-speed; {@code comboFactor} is {@code
     * 1.0} whenever the pocket servo was off/not-this-attacker or had no lever;
     * both are {@code 1.0} when the hit was suppressed before compute ({@code
     * vector == null}). Each carries only kernel/JDK-1.0 types, so it crosses the
     * plugin boundary safely (D-8).
     *
     * <p>Additive growth: the two-arg constructor (2.4.1's arity) defaults {@code
     * comboFactor} to {@code 1.0}, so every pre-servo construction is unchanged.</p>
     */
    public record Paced(KnockbackVector vector, double paceFactor, double comboFactor) {

        /** The pre-servo arity (2.4.1): no combo factor applied. */
        public Paced(KnockbackVector vector, double paceFactor) {
            this(vector, paceFactor, 1.0);
        }
    }

    /** Melee knockback: base push away from the attacker plus yaw-directed bonus levels. */
    public static KnockbackVector compute(
            EntityState attacker,
            EntityState victim,
            KnockbackProfile profile,
            Double victimYOverride) {
        return compute(attacker, victim, profile, victimYOverride, ThreadLocalRandom.current(), false);
    }

    public static KnockbackVector compute(
            EntityState attacker,
            EntityState victim,
            KnockbackProfile profile,
            Double victimYOverride,
            RandomGenerator random,
            boolean freshSprint) {
        return computePaced(attacker, victim, profile, victimYOverride, random, freshSprint).vector();
    }

    /**
     * Melee knockback returning both the vector and the pace factor applied — the
     * journal-attribution overload (D-6), with the pocket servo OFF. Delegates to
     * the servo overload with {@link PocketServoConfig#INACTIVE}, so it is
     * byte-identical to {@link #compute} for the vector; the {@link
     * Paced#paceFactor()} is what the delivery journal records.
     */
    public static Paced computePaced(
            EntityState attacker,
            EntityState victim,
            KnockbackProfile profile,
            Double victimYOverride,
            RandomGenerator random,
            boolean freshSprint) {
        return computePaced(
                attacker, victim, profile, victimYOverride, random, freshSprint, PocketServoConfig.INACTIVE);
    }

    /**
     * Melee knockback with the pocket servo but WITHOUT the precision seam — the
     * base-correctness path (combo-hold §3.2b; precision-derivation §6 row 1). The
     * servo still branches on the victim's launch ground state (the mandatory
     * grounded-vs-air drag branch — the pure-air sum overshoots a grounded launch
     * by ~1.9 blocks), but every precision extra (victim self-drift, ping horizons,
     * ground tail, dynamic target) is off; the launch slip defaults to stone. Used
     * by the pace-only-plus-servo callers and the kernel pins that stage no
     * predictor inputs. A core caller with the wired seam uses the {@link
     * PredictorInputs} overload instead.
     */
    public static Paced computePaced(
            EntityState attacker,
            EntityState victim,
            KnockbackProfile profile,
            Double victimYOverride,
            RandomGenerator random,
            boolean freshSprint,
            PocketServoConfig servo) {
        PredictorInputs degraded = PredictorInputs.degraded(
                victim.grounded(), Decay.DEFAULT_SLIPPERINESS, victim.moveSpeedAttr());
        return computePaced(attacker, victim, profile, victimYOverride, random, freshSprint, servo, degraded);
    }

    /**
     * Melee knockback with the pocket servo (combo-hold §3.2), returning the
     * vector plus the pace AND combo factors applied. The servo factor {@code σ}
     * — {@link PocketServo}'s exact inverse solve of the era flight equations —
     * scales the FRESH horizontal knock exactly like pace does: the post-taper
     * base push (step 4's {@code −direction × push}) and the sprint/wtap/enchant
     * extras (step 6), NEVER the friction-carried residual and NEVER the vertical.
     * The two factors compose multiplicatively on the fresh contribution
     * ({@code fresh = (base − rangeTaper) × pace × combo}), each touching each
     * component once. At {@link PocketServoConfig#INACTIVE} — module off,
     * not-this-attacker, or no lever — {@code σ == 1.0} and this is byte-identical
     * to the pace-only path (zero-touch).
     *
     * <p>The servo reads the shipped vertical stamp (which {@code σ} never scales)
     * to bound the flight window, so the vertical is computed first, independent
     * of {@code σ}; then {@code σ} folds into the single {@code freshFactor =
     * pace × σ} the base push and extras share, and {@code finish} runs on the
     * combined vector exactly as before.</p>
     *
     * <p><b>Precision seam (§3.2b).</b> {@code predictorInputs} carries every
     * per-hit quantity beyond {@link EntityState} the precision solve needs (victim
     * self-drift, ping horizons, launch slip, ground-tail locomotion, the dynamic
     * target's geometry) — one immutable value the netty and region sites freeze.
     * {@link EntityState} grows no arity; the record is the whole seam. At
     * {@link PocketServoConfig#INACTIVE} it is never consulted (byte-identical).</p>
     */
    public static Paced computePaced(
            EntityState attacker,
            EntityState victim,
            KnockbackProfile profile,
            Double victimYOverride,
            RandomGenerator random,
            boolean freshSprint,
            PocketServoConfig servo,
            PredictorInputs predictorInputs) {

        if (profile.modern().enabled()) {
            // The modern (Paper 26.1.2) melee formula: a self-contained compute
            // that owns knockback-resistance internally via the vanilla fractional
            // (1 − r), so the LEGACY roll below and the SCALING horizontal multiply
            // in finish() are both bypassed. Pace scaling and the pocket servo do
            // not participate in v1 (factors 1.0/1.0) — modern is cooldown-free
            // vanilla math, not the combo-equilibrium the servo tunes. The branch
            // lives inside this overload so both the netty pre-send call site and
            // the authoritative call site select it off the frozen PlayerView
            // profile with zero call-site change.
            return new Paced(modernCompute(attacker, victim, profile, victimYOverride, random), 1.0, 1.0);
        }

        if (resistanceCancels(victim, profile, random)) {
            return new Paced(null, 1.0, 1.0); // suppressed before compute ⇒ no factors applied
        }
        // Speed-conformal factor: scales the fresh horizontal knock (base push +
        // extras), 1.0 when off/base-speed (then every ×freshFactor below is skipped).
        // The attribute is walk-stance-normalized at capture, so the sprint flag no
        // longer selects the baseline (F1) — a single 0.10 walk baseline serves both.
        double pace = PaceScale.factor(attacker.moveSpeedAttr(), profile.paceScaling());
        double taper = profile.rangeReduction().reductionAt(distance(attacker, victim));

        // The pocket servo σ: the exact inverse solve, computed BEFORE the vector so
        // its inputs (the σ=1 fresh horizontal and the shipped vertical stamp) are the
        // era values the flight model expects. 1.0 (skipped) when the servo is
        // inactive for this hit.
        double combo = servo.active()
                ? servoFactor(attacker, victim, profile, victimYOverride, pace, taper,
                        freshSprint, servo, predictorInputs).sigma()
                : 1.0;
        double freshFactor = pace * combo;

        double[] vector = base(victim, attacker.x(), attacker.z(), profile, victimYOverride, random, taper, freshFactor);

        double sprintLevels = attacker.sprinting() ? profile.sprintFactor() : 0.0;
        double enchantLevels = attacker.knockbackEnchantLevel();
        if (sprintLevels + enchantLevels > 0) {
            boolean wtap = profile.wtapExtra().enabled() && sprintLevels > 0 && freshSprint;
            double sprintHorizontal = wtap ? profile.wtapExtra().horizontal() : profile.extra().horizontal();
            double horizontalBonus =
                    sprintLevels * sprintHorizontal + enchantLevels * profile.extra().horizontal();
            if (freshFactor != 1.0) {
                horizontalBonus *= freshFactor; // fresh extras scale; the flat vertical bonus never does
            }
            double yawRadians = Math.toRadians(attacker.yaw());
            vector[0] += -Math.sin(yawRadians) * horizontalBonus;
            vector[2] += Math.cos(yawRadians) * horizontalBonus;
            vector[1] += wtap ? profile.wtapExtra().vertical() : profile.extra().vertical();
        }

        return new Paced(finish(vector, victim, profile), pace, combo);
    }

    /**
     * The {@link java.util.Random} delivery of the servo {@link #computePaced(EntityState,
     * EntityState, KnockbackProfile, Double, RandomGenerator, boolean, PocketServoConfig,
     * PredictorInputs)} — a Java-8-native overload so the downgraded mega-jar never bakes a
     * jvmdowngrader {@code RandomGenerator} stub type into a public descriptor that crosses a
     * plugin boundary (campaign D-8). {@code java.util.random.RandomGenerator} is a Java-17 type
     * jvmdowngrader rewrites to a shaded stub whose FQN carries a per-plugin relocation prefix;
     * two plugins that share such a descriptor (the tester calling into the kernel shipped in the
     * Mental jar) resolve mismatched stub types and {@code NoSuchMethodError} on Java 8. {@code
     * java.util.Random} is a Java-1.0 type present verbatim in every bytecode tier, so a descriptor
     * over it is stable across the boundary. This is the exact sibling of the {@code computeBase(…,
     * java.util.Random)} precedent for the servo compute path the combo-hold round added. Additive-
     * only: the {@code RandomGenerator} overload above is untouched, so the kernel's frozen-core
     * invariant and the api japicmp gate stay green.
     *
     * <p>The cast to {@code RandomGenerator} is load-bearing: a bare {@code computePaced(…, random,
     * …)} would re-select THIS overload (most-specific) and recurse forever. It is legal because
     * {@code java.util.Random} implements {@code RandomGenerator} on Java 17+, and jvmdowngrader
     * adapts the {@code Random ->} stub conversion inside this jar (never across the boundary).</p>
     */
    public static Paced computePaced(
            EntityState attacker,
            EntityState victim,
            KnockbackProfile profile,
            Double victimYOverride,
            Random random,
            boolean freshSprint,
            PocketServoConfig servo,
            PredictorInputs predictorInputs) {
        return computePaced(
                attacker, victim, profile, victimYOverride, (RandomGenerator) random,
                freshSprint, servo, predictorInputs);
    }

    /**
     * The full pocket-servo solve for one melee hit (combo-hold §3.2/§3.2b),
     * exposed so the core can push the {@link PocketServo.Solution} to the debug
     * sink for the lab round WITHOUT re-deriving the era quantities the engine
     * already extracts. Recomputes the pace and taper (cheap) and defers to
     * {@link #servoFactor}. The engine's own compute path shares the same solve.
     */
    public static PocketServo.Solution explainServo(
            EntityState attacker,
            EntityState victim,
            KnockbackProfile profile,
            Double victimYOverride,
            boolean freshSprint,
            PocketServoConfig servo,
            PredictorInputs predictorInputs) {
        double pace = PaceScale.factor(attacker.moveSpeedAttr(), profile.paceScaling());
        double taper = profile.rangeReduction().reductionAt(distance(attacker, victim));
        return servoFactor(attacker, victim, profile, victimYOverride, pace, taper,
                freshSprint, servo, predictorInputs);
    }

    /**
     * The pocket-servo solve for one melee hit (combo-hold §3.2/§3.2b). Extracts the
     * era quantities the {@link PocketServo} solve needs — the horizontal separation
     * {@code d0}, the σ=1 fresh horizontal and the friction-carried residual both
     * projected on the attacker→victim axis (the axis-projection-everywhere rule;
     * a residual moving the victim toward the attacker is now correctly NEGATIVE),
     * and the shipped vertical stamp — then runs the precision inverse solve over
     * {@code predictorInputs}. The fresh and residual carry the airborne
     * air-horizontal multiplier (matching {@code finish}); the horizontal cap and
     * scaling-resistance are the documented v1 approximations the clamps bound.
     */
    private static PocketServo.Solution servoFactor(
            EntityState attacker,
            EntityState victim,
            KnockbackProfile profile,
            Double victimYOverride,
            double pace,
            double taper,
            boolean freshSprint,
            PocketServoConfig servo,
            PredictorInputs predictorInputs) {
        double deltaX = attacker.x() - victim.x();
        double deltaZ = attacker.z() - victim.z();
        double separation = Math.sqrt(deltaX * deltaX + deltaZ * deltaZ);
        // The attacker→victim unit axis u (pointing AWAY from the attacker); every
        // directional quantity below projects onto it.
        double ux = 0.0;
        double uz = 0.0;
        if (separation > 1.0e-9) {
            ux = -deltaX / separation;
            uz = -deltaZ / separation;
        }

        // The fresh horizontal contribution at σ=1 (pace-scaled): the radial base
        // push (directed AWAY from the attacker, matching base()'s −pushX) plus the
        // yaw-directed extras.
        double push = Math.max(0.0, profile.base().horizontal() - taper) * pace;
        double freshX = ux * push;
        double freshZ = uz * push;
        double sprintLevels = attacker.sprinting() ? profile.sprintFactor() : 0.0;
        double enchantLevels = attacker.knockbackEnchantLevel();
        boolean bonus = sprintLevels + enchantLevels > 0;
        boolean wtap = profile.wtapExtra().enabled() && sprintLevels > 0 && freshSprint;
        if (bonus) {
            double sprintHorizontal = wtap ? profile.wtapExtra().horizontal() : profile.extra().horizontal();
            double horizontalBonus =
                    (sprintLevels * sprintHorizontal + enchantLevels * profile.extra().horizontal()) * pace;
            double yawRadians = Math.toRadians(attacker.yaw());
            freshX += -Math.sin(yawRadians) * horizontalBonus;
            freshZ += Math.cos(yawRadians) * horizontalBonus;
        }
        double airHorizontal = victim.grounded() ? 1.0 : profile.air().horizontal();
        double freshEra = (freshX * ux + freshZ * uz) * airHorizontal;
        double residualCarry =
                (victim.vx() * profile.friction().x() * ux + victim.vz() * profile.friction().z() * uz)
                        * airHorizontal;
        // The shipped vertical stamp: the engine's own faithful era replay (the servo
        // never shapes it, only reads it to bound the flight window).
        double verticalStamp = shippedVertical(victim, profile, victimYOverride, bonus, wtap);

        return PocketServo.solve(
                servo, predictorInputs, separation, residualCarry, freshEra,
                verticalStamp, attacker.moveSpeedAttr());
    }

    /**
     * The vertical the knock ships — a faithful, random-free replay of the
     * vertical path through {@link #base} and {@link #finish} (SET/ADD base,
     * vertical cap on the base, flat extra vertical, air multiplier, WindSpigot
     * vertical add, vertical-min floor, packet clamp). The pocket servo feeds it
     * to the air-time sim; {@code σ} never touches it, so the value is identical
     * whether taken here or off the finished vector. Mirrors the engine's vertical
     * ordering EXACTLY — kept in step with {@link #base}/{@link #finish} by hand
     * (the engine is frozen additive-only, so the two do not drift).
     */
    private static double shippedVertical(
            EntityState victim, KnockbackProfile profile, Double victimYOverride, boolean bonus, boolean wtap) {
        double victimVy = victimYOverride != null ? victimYOverride : victim.vy();
        double y = profile.verticalMode() == VerticalMode.SET
                ? profile.base().vertical()
                : victimVy * profile.friction().y() + profile.base().vertical();
        if (profile.limits().limitsVertical() && y > profile.limits().vertical()) {
            y = profile.limits().vertical();
        }
        if (bonus) {
            y += wtap ? profile.wtapExtra().vertical() : profile.extra().vertical();
        }
        if (!victim.grounded()) {
            y *= profile.air().vertical();
        }
        if (profile.add().vertical() != 0.0) {
            y += Math.signum(y) * profile.add().vertical();
        }
        if (y < profile.limits().verticalMin()) {
            y = profile.limits().verticalMin();
        }
        return Math.max(-PACKET_CLAMP, Math.min(PACKET_CLAMP, y));
    }

    /**
     * Base-only knockback away from a source position — the bare legacy
     * {@code knockBack(0.4)} that rod bobbers and thrown projectiles
     * triggered. No bonus levels and no range taper exist on this path.
     */
    public static KnockbackVector computeBase(
            EntityState victim,
            double sourceX,
            double sourceZ,
            KnockbackProfile profile,
            Double victimYOverride,
            RandomGenerator random) {

        if (resistanceCancels(victim, profile, random)) {
            return null;
        }
        // Projectiles/rods are unaffected by pace scaling in v1 (era rod/arrow
        // knocks are shooter-position stamps; the combo equilibrium is a melee
        // phenomenon) — pace 1.0.
        return finish(
                base(victim, sourceX, sourceZ, profile, victimYOverride, random, 0.0, 1.0), victim, profile);
    }

    /**
     * The {@link java.util.Random} delivery of {@link #computeBase(EntityState, double,
     * double, KnockbackProfile, Double, RandomGenerator)} — a Java-8-native overload so
     * the downgraded mega-jar never bakes a jvmdowngrader {@code RandomGenerator} stub
     * type into a public descriptor that crosses a plugin boundary (campaign D-8).
     * {@code java.util.random.RandomGenerator} is a Java-17 type jvmdowngrader rewrites
     * to a shaded stub whose FQN carries a per-plugin relocation prefix; two plugins that
     * share such a descriptor (the tester calling into the kernel shipped in the Mental
     * jar) resolve mismatched stub types and {@code NoSuchMethodError} on Java 8. {@code
     * java.util.Random} is a Java-1.0 type present verbatim in every bytecode tier, so a
     * descriptor over it is stable across the boundary. Additive-only: the
     * {@code RandomGenerator} overload above is untouched, so the kernel's frozen-core
     * invariant and the api japicmp gate stay green.
     *
     * <p>The cast to {@code RandomGenerator} is load-bearing: a bare {@code
     * computeBase(…, random)} would re-select THIS overload (most-specific) and recurse
     * forever. It is legal because {@code java.util.Random} implements {@code
     * RandomGenerator} on Java 17+, and jvmdowngrader adapts the {@code Random ->} stub
     * conversion inside this jar (never across the boundary).</p>
     */
    public static KnockbackVector computeBase(
            EntityState victim,
            double sourceX,
            double sourceZ,
            KnockbackProfile profile,
            Double victimYOverride,
            Random random) {
        return computeBase(victim, sourceX, sourceZ, profile, victimYOverride, (RandomGenerator) random);
    }

    /** Clamps each axis to the legacy packet limit; bonus additions re-clamp through this. */
    public static KnockbackVector clamp(double x, double y, double z) {
        return new KnockbackVector(
                Math.max(-PACKET_CLAMP, Math.min(PACKET_CLAMP, x)),
                Math.max(-PACKET_CLAMP, Math.min(PACKET_CLAMP, y)),
                Math.max(-PACKET_CLAMP, Math.min(PACKET_CLAMP, z)));
    }

    private static boolean resistanceCancels(
            EntityState victim, KnockbackProfile profile, RandomGenerator random) {
        return profile.resistance() == ResistancePolicy.LEGACY
                && victim.knockbackResistance() > 0.0
                && random.nextDouble() < victim.knockbackResistance();
    }

    private static double distance(EntityState attacker, EntityState victim) {
        double dx = attacker.x() - victim.x();
        double dy = attacker.y() - victim.y();
        double dz = attacker.z() - victim.z();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * The modern (Paper 26.1.2) melee formula — decompiled from the server jar,
     * constants read from the constant pool; the grounded closed form matches the
     * live-measured modern wire (standing {@code (0.4, 0.3608)}, sprint
     * {@code (0.7, 0.4)}). A melee hit is two sequential applications of the
     * vanilla {@code knockback(strength, x, z)} core:
     *
     * <pre>
     *   1. base   knock(baseStrength, victim − attacker.POSITION)  — every hit
     *   2. extra  knock(sprintBonus? + kbLevels×enchantBonus, attacker.FACING)
     *             — only when the bonus is positive
     * </pre>
     *
     * <p>Each application scales its added strength by {@code (1 − resistance)}
     * (so the family owns knockback-resistance internally — the legacy LEGACY roll
     * and the SCALING horizontal multiply are both skipped), halves the surviving
     * motion by {@code residualHorizontal}/{@code residualVertical} (the vanilla
     * {@code ÷ 2}), and — grounded, or airborne with the downward toggle off —
     * lifts the vertical to {@code min(verticalCap, vy·residualVertical +
     * strength)}. An airborne victim with the toggle on keeps its own vy (zero
     * lift — the modern mid-air slam). {@code victimYOverride} replaces the INPUT
     * vy only, the same latency-compensation substitution the legacy ADD path
     * makes. The shared post-pipeline (air multipliers, add offsets, vertical-min
     * floor, ±3.9 clamp) then runs exactly as {@link #finish}, minus the
     * resistance-policy multiply.</p>
     *
     * <p><b>Sign convention.</b> The legacy {@link #base} computes
     * {@code (source − victim)} and SUBTRACTS the scaled push; here the direction
     * is {@code (victim − source)} and the push is ADDED — the identical outward
     * vector (away from the attacker), expressed the vanilla-26.1.2 way (its core
     * normalizes the caller's {@code (x, z)}, and the hurt path passes
     * {@code source − victim} which the core then subtracts). Pinned by a
     * directional unit test.</p>
     */
    private static KnockbackVector modernCompute(
            EntityState attacker,
            EntityState victim,
            KnockbackProfile profile,
            Double victimYOverride,
            RandomGenerator random) {

        ModernKnockback m = profile.modern();
        double resistance = Math.max(0.0, Math.min(1.0, victim.knockbackResistance()));
        boolean grounded = victim.grounded();
        boolean liftsVertical = grounded || !m.downwardKnockback();

        double hx = victim.vx() * m.residualHorizontal();
        double hz = victim.vz() * m.residualHorizontal();
        double vy = victimYOverride != null ? victimYOverride : victim.vy();

        // Stage 1 — base knock, directed away from the attacker's position.
        double deltaX = victim.x() - attacker.x();
        double deltaZ = victim.z() - attacker.z();
        while (deltaX * deltaX + deltaZ * deltaZ < 1.0e-5) {
            deltaX = (random.nextDouble() - random.nextDouble()) * 0.01;
            deltaZ = (random.nextDouble() - random.nextDouble()) * 0.01;
        }
        double magnitude = Math.sqrt(deltaX * deltaX + deltaZ * deltaZ);
        double strengthOne = m.baseStrength() * (1.0 - resistance);
        hx += (deltaX / magnitude) * strengthOne;
        hz += (deltaZ / magnitude) * strengthOne;
        double y = liftsVertical
                ? cappedVertical(vy * m.residualVertical() + strengthOne, m.verticalCap())
                : vy;

        // Stage 2 — extra knock, directed along the attacker's facing (yaw). The
        // sprint bonus rides the same SprintWire verdict the legacy path reads
        // ({@code attacker.sprinting()}), not the vanilla attack-strength gate —
        // Mental's product is cooldown-free combat.
        double bonus = (attacker.sprinting() ? m.sprintBonus() : 0.0)
                + attacker.knockbackEnchantLevel() * m.enchantBonus();
        if (bonus > 0.0) {
            double strengthTwo = bonus * (1.0 - resistance);
            double yawRadians = Math.toRadians(attacker.yaw());
            hx = hx * m.residualHorizontal() - Math.sin(yawRadians) * strengthTwo;
            hz = hz * m.residualHorizontal() + Math.cos(yawRadians) * strengthTwo;
            y = liftsVertical
                    ? cappedVertical(y * m.residualVertical() + strengthTwo, m.verticalCap())
                    : y;
        }

        // Shared post-pipeline (finish() minus the resistance-policy multiply).
        if (!grounded) {
            hx *= profile.air().horizontal();
            hz *= profile.air().horizontal();
            y *= profile.air().vertical();
        }
        double[] vector = {hx, y, hz};
        applyAdd(vector, profile.add());
        if (vector[1] < profile.limits().verticalMin()) {
            vector[1] = profile.limits().verticalMin();
        }
        return clamp(vector[0], vector[1], vector[2]);
    }

    /** The modern grounded vertical ceiling: {@code min(cap, v)} when the cap is positive, else uncapped. */
    private static double cappedVertical(double value, double cap) {
        return cap > 0.0 ? Math.min(cap, value) : value;
    }

    private static double[] base(
            EntityState victim,
            double sourceX,
            double sourceZ,
            KnockbackProfile profile,
            Double victimYOverride,
            RandomGenerator random,
            double pushReduction,
            double paceFactor) {

        double deltaX = sourceX - victim.x();
        double deltaZ = sourceZ - victim.z();
        if (deltaX * deltaX + deltaZ * deltaZ < 1.0e-4) {
            deltaX = (random.nextDouble() - random.nextDouble()) * 0.01;
            deltaZ = (random.nextDouble() - random.nextDouble()) * 0.01;
            if (deltaX * deltaX + deltaZ * deltaZ < 1.0e-8) {
                deltaX = 0.01;
            }
        }
        double magnitude = Math.sqrt(deltaX * deltaX + deltaZ * deltaZ);
        double push = Math.max(0.0, profile.base().horizontal() - pushReduction);

        // The fresh directional push is the knock Mental adds, so pace scaling
        // multiplies it here; the friction-carried residual below is the victim's
        // own motion (already scaled in the ledger) and is left untouched (A3).
        double pushX = (deltaX / magnitude) * push;
        double pushZ = (deltaZ / magnitude) * push;
        if (paceFactor != 1.0) {
            pushX *= paceFactor;
            pushZ *= paceFactor;
        }

        // SET assigns the vertical outright — the victim's motion (and with it
        // the latency-compensation vertical hint) is irrelevant by definition.
        double victimVy = victimYOverride != null ? victimYOverride : victim.vy();
        double x = victim.vx() * profile.friction().x() - pushX;
        double y = profile.verticalMode() == VerticalMode.SET
                ? profile.base().vertical()
                : victimVy * profile.friction().y() + profile.base().vertical();
        double z = victim.vz() * profile.friction().z() - pushZ;

        if (profile.limits().limitsHorizontal()) {
            double horizontal = Math.hypot(x, z);
            if (horizontal > profile.limits().horizontal()) {
                double scale = profile.limits().horizontal() / horizontal;
                x *= scale;
                z *= scale;
            }
        }

        if (profile.limits().limitsVertical() && y > profile.limits().vertical()) {
            y = profile.limits().vertical();
        }
        return new double[] {x, y, z};
    }

    private static KnockbackVector finish(double[] vector, EntityState victim, KnockbackProfile profile) {
        if (!victim.grounded()) {
            vector[0] *= profile.air().horizontal();
            vector[2] *= profile.air().horizontal();
            vector[1] *= profile.air().vertical();
        }

        applyAdd(vector, profile.add());

        if (vector[1] < profile.limits().verticalMin()) {
            vector[1] = profile.limits().verticalMin();
        }

        if (profile.resistance() == ResistancePolicy.SCALING && victim.knockbackResistance() > 0.0) {
            double survives = 1.0 - victim.knockbackResistance();
            vector[0] *= survives;
            vector[2] *= survives;
        }
        return clamp(vector[0], vector[1], vector[2]);
    }

    /**
     * WindSpigot's add offsets: the horizontal addition distributes across
     * X/Z by each axis's share of the motion and matches its sign, the
     * vertical addition matches the vertical sign — a zero axis (or a zero
     * vector) receives nothing, so the offset can never reverse a direction.
     */
    private static void applyAdd(double[] vector, KnockbackProfile.Push add) {
        if (add.horizontal() != 0.0) {
            double absX = Math.abs(vector[0]);
            double absZ = Math.abs(vector[2]);
            double total = absX + absZ;
            if (total > 1.0e-9) {
                vector[0] += Math.signum(vector[0]) * add.horizontal() * (absX / total);
                vector[2] += Math.signum(vector[2]) * add.horizontal() * (absZ / total);
            }
        }
        if (add.vertical() != 0.0) {
            vector[1] += Math.signum(vector[1]) * add.vertical();
        }
    }
}
