#!/bin/bash
# ──────────────────────────────────────────────────────────────────────────
#  Concurrent live-server integration matrix.
#
#  Boots EVERY version at the same time — each server on its own port, each
#  with the Mental + MentalTester jars injected via --add-plugin (the same
#  mechanism the Gradle tasks use), reusing run-paper's downloaded Paper
#  jars and the per-version run/ directories. The tester runs its suite and
#  shuts its server down; this script collects PASS/FAIL per version and
#  fails if anything but PASS comes back.
#
#  Wall-clock is the SLOWEST single server (~2–3 minutes) instead of the
#  sum of all of them. Live progress streams into run/matrix-live.log as
#  "[version] RUN/PASS/FAIL <case>" lines — tail it to watch every server.
#
#  The Gradle tasks (integrationTest / integrationTestMatrix /
#  integrationTestOcm) remain the canonical sequential path (CI uses them);
#  this is the fast local gate.
#
#  Usage:  scripts/integration-matrix.sh [--versions <v1>,<v2>] [--no-ocm]
#  Exit:   0 = every server PASS, 1 = anything else.
#
#  Versions, per-version JDK, and the OCM floor/ceiling all come from
#  support-matrix.json (via jq) — THE single source of truth. No version or
#  JDK literal lives in this script.
# ──────────────────────────────────────────────────────────────────────────
set -u
cd "$(dirname "$0")/.."

MATRIX="$PWD/support-matrix.json"
command -v jq >/dev/null 2>&1 || { echo "jq is required (reads support-matrix.json)" >&2; exit 2; }

# Newest first: the heavy modern servers ignite (paperclip, JIT, chunk IO)
# while nothing else runs; the lightweight Java-17 trio boots last into a
# calm machine instead of reaching its longest suites mid-ignition. The
# descriptor lists oldest -> newest, so reverse it.
VERSIONS_DEFAULT="$(jq -r '[.entries[] | select(.platform=="paper") | .version] | reverse | join(" ")' "$MATRIX")"
# OCM runs on the floor and ceiling paper entries.
OCM_VERSIONS="$(jq -r '[.entries[] | select(.platform=="paper") | .version] | .[0] + " " + .[-1]' "$MATRIX")"
JAR_CACHE="$HOME/.gradle/caches/run-task-jars/paper/jars"
OCM_JAR="$PWD/run/ocm-jar/OldCombatMechanics.jar"
LIVE="$PWD/run/matrix-live.log"
VERDICTS="$PWD/run/matrix-verdicts.txt"
SERVER_TIMEOUT_SECONDS=420
BASE_PORT=25600

VERSIONS="$VERSIONS_DEFAULT"
WITH_OCM=auto
while [ $# -gt 0 ]; do
    case "$1" in
        --versions) VERSIONS="$(echo "$2" | tr ',' ' ')"; shift 2 ;;
        --no-ocm) WITH_OCM=no; shift ;;
        --ocm) WITH_OCM=yes; shift ;;
        *) echo "unknown argument: $1" >&2; exit 2 ;;
    esac
done
if [ "$WITH_OCM" = auto ]; then
    [ -f "$OCM_JAR" ] && WITH_OCM=yes || WITH_OCM=no
fi

JAVA17="$(/usr/libexec/java_home -v 17 2>/dev/null)/bin/java"
JAVA25="$(/usr/libexec/java_home -v 25 2>/dev/null)/bin/java"
# The per-version JDK is declared in the descriptor (17 for the pre-1.20.5
# class-file targets, 25 otherwise). An off-matrix override version falls back
# to the newest toolchain.
java_for() {
    local jdk
    jdk=$(jq -r --arg v "$1" '.entries[] | select(.version==$v) | .jdk' "$MATRIX")
    case "$jdk" in
        17) echo "$JAVA17" ;;
        *) echo "$JAVA25" ;;
    esac
}

MENTAL_JAR=$(ls "$PWD"/core/build/libs/Mental-*.jar 2>/dev/null | sort -V | tail -1)
TESTER_JAR=$(ls "$PWD"/tester/build/libs/MentalTester-*.jar 2>/dev/null | sort -V | tail -1)
if [ -z "$MENTAL_JAR" ] || [ -z "$TESTER_JAR" ]; then
    echo "plugin jars missing — run ./gradlew build first" >&2
    exit 2
fi

mkdir -p run
: > "$LIVE"
: > "$VERDICTS"

# A killed previous run can leave servers alive holding world session.lock
# files (and ports). Their command lines all reference the run-paper jar
# cache, so this is precise.
pkill -f "run-task-jars/paper/jars" 2>/dev/null && sleep 2

PIDS=""
cleanup() {
    for pid in $PIDS; do
        pkill -P "$pid" 2>/dev/null
        kill "$pid" 2>/dev/null
    done
}
trap cleanup INT TERM

# run_one <version> <run-dir> <port> <label> <ocm|plain>
run_one() {
    local version=$1 dir=$2 port=$3 label=$4 flavour=$5
    local jar java log result
    jar=$(ls "$JAR_CACHE/$version"/*.jar 2>/dev/null | sort -V | tail -1)
    if [ -z "$jar" ]; then
        echo "[$label] no cached Paper jar — run the Gradle task once to download it" >> "$LIVE"
        echo "$label NO-JAR" >> "$VERDICTS"
        return
    fi
    java=$(java_for "$version")
    log="$dir/matrix-run.log"
    result="$dir/plugins/MentalTester/test-results.txt"

    mkdir -p "$dir"
    # Mirror the Gradle doFirst: pristine plugin config trees per run.
    rm -rf "$dir/plugins/Mental"
    rm -f "$result" "$dir/plugins/MentalTester/test-failures.txt"
    [ "$flavour" = ocm ] && rm -rf "$dir/plugins/OldCombatMechanics"
    echo "eula=true" > "$dir/eula.txt"
    if [ ! -f "$dir/server.properties" ]; then
        printf 'level-type=flat\nonline-mode=false\nspawn-protection=0\nview-distance=4\nsimulation-distance=4\nmotd=Mental integration test\n' \
            > "$dir/server.properties"
    fi

    # The single-dash -add-plugin= form is what run-paper itself uses across
    # this whole version range — proven from the floor up.
    local plugin_args="-add-plugin=$MENTAL_JAR -add-plugin=$TESTER_JAR"
    [ "$flavour" = ocm ] && plugin_args="$plugin_args -add-plugin=$OCM_JAR"

    # A fresh freshness nonce per boot: the tester echoes it into the verdict
    # line ("PASS nonce=<n>"), and we accept ONLY this nonce below — a leftover
    # test-results.txt from an earlier boot can never be read as a PASS.
    local nonce
    nonce=$(uuidgen 2>/dev/null || echo "$RANDOM$RANDOM$RANDOM")

    echo "[$label] booting on port $port" >> "$LIVE"
    # 768M is generous for a flat test world; small heaps keep nine
    # concurrent JVMs far away from memory pressure (page-fault storms
    # read as 30s+ tick stalls). caffeinate -i stops macOS from App-Napping
    # backgrounded servers — a napped JVM stalls without ever logging lag.
    local keepawake=""
    command -v caffeinate >/dev/null 2>&1 && keepawake="caffeinate -i"
    ( cd "$dir" && exec $keepawake "$java" -Xmx768M -Dcom.mojang.eula.agree=true \
            -Ddisable.watchdog=true -Dmental.tester.nonce="$nonce" \
            -jar "$jar" --nogui --port "$port" $plugin_args ) > "$log" 2>&1 &
    local server=$!

    # Live relay: boot marker + every test case transition, label-prefixed.
    tail -f "$log" 2>/dev/null | sed -l -n -E \
        -e "s/^.*Done \(([0-9.]+)s\)!.*$/[$label] server up (\1s)/p" \
        -e "s/^.*\[MentalTester\] \[test\] (RUN|PASS|FAIL)(.*)$/[$label] \1\2/p" \
        >> "$LIVE" &
    local relay=$!

    ( sleep "$SERVER_TIMEOUT_SECONDS" && kill -9 "$server" 2>/dev/null ) &
    local watchdog=$!

    wait "$server" 2>/dev/null
    pkill -P "$watchdog" 2>/dev/null; kill "$watchdog" 2>/dev/null
    sleep 0.5
    kill "$relay" 2>/dev/null

    # Verdict is the tester's line ONLY if it carries this boot's nonce; any
    # other content (missing, wrong nonce, malformed) is not a PASS.
    local verdict="NO-RESULT"
    if [ -f "$result" ]; then
        local raw
        raw=$(cat "$result")
        if [ "$raw" = "PASS nonce=$nonce" ]; then
            verdict="PASS"
        elif [ "$raw" = "FAIL nonce=$nonce" ]; then
            verdict="FAIL"
        elif printf '%s' "$raw" | grep -q "nonce=$nonce"; then
            verdict="ODD($raw)"
        else
            verdict="STALE($raw)"
        fi
    fi
    echo "[$label] finished: $verdict (log: $log)" >> "$LIVE"
    echo "$label $verdict" >> "$VERDICTS"
}

echo "── concurrent matrix: $VERSIONS $([ "$WITH_OCM" = yes ] && echo "+ OCM($OCM_VERSIONS)") ──" | tee -a "$LIVE"

# Staggered launches: server BOOT is the CPU spike (world load, class
# loading); offsetting starts by a few seconds keeps any one server's tick
# thread from starving while the suites themselves overlap freely.
STAGGER_SECONDS=3
port=$BASE_PORT
for v in $VERSIONS; do
    run_one "$v" "$PWD/run/$v" "$port" "$v" plain &
    PIDS="$PIDS $!"
    port=$((port + 1))
    sleep "$STAGGER_SECONDS"
done
if [ "$WITH_OCM" = yes ]; then
    for v in $OCM_VERSIONS; do
        run_one "$v" "$PWD/run/ocm/$v" "$port" "$v+OCM" ocm &
        PIDS="$PIDS $!"
        port=$((port + 1))
        sleep "$STAGGER_SECONDS"
    done
fi

for pid in $PIDS; do
    wait "$pid" 2>/dev/null
done

echo "" | tee -a "$LIVE"
echo "── matrix summary ──" | tee -a "$LIVE"
sort "$VERDICTS" | tee -a "$LIVE"

if grep -qv " PASS$" "$VERDICTS"; then
    echo "MATRIX FAILED" | tee -a "$LIVE"
    exit 1
fi
echo "MATRIX PASSED ($(wc -l < "$VERDICTS" | tr -d ' ') servers)" | tee -a "$LIVE"
