#!/bin/bash
# ──────────────────────────────────────────────────────────────────────────
#  Concurrent live-server integration matrix.
#
#  Boots EVERY version at the same time — each server on its own port, each
#  with the Mental + MentalTester jars copied into its plugins/ dir (the
#  legacy Paper builds do not parse -add-plugin=; copying is universal),
#  reusing run-paper's downloaded Paper jars and the per-version run/
#  directories. Each server runs on its per-entry JDK — the newest clean
#  flagless rung for legacy versions. The tester runs its suite and
#  shuts its server down; this script collects PASS/FAIL per version and
#  fails if anything but PASS comes back.
#
#  Wall-clock is the SLOWEST single server (~2–3 minutes) instead of the
#  sum of all of them. Live progress streams into run/matrix-live.log as
#  "[version] RUN/PASS/FAIL <case>" lines — tail it to watch every server.
#
#  The Gradle tasks (integrationTest / integrationTestMatrix) remain the
#  canonical sequential path (CI uses them); this is the fast local gate.
#
#  Usage:  scripts/integration-matrix.sh [--versions <v1>,<v2>]
#  Exit:   0 = every server PASS, 1 = anything else.
#
#  Versions and per-version JDK all come from
#  support-matrix.json (via jq) — THE single source of truth. No version or
#  JDK literal lives in this script.
#
#  This concurrent gate is PAPER-only (it boots from the cached run-paper Paper
#  jars directly). The folia entry needs run-paper's Folia downloads service, so
#  it runs via the Gradle path instead: `./gradlew integrationTestFolia` (or the
#  full `integrationTestMatrix`, which chains it after the paper entries).
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
JAR_CACHE="$HOME/.gradle/caches/run-task-jars/paper/jars"
LIVE="$PWD/run/matrix-live.log"
VERDICTS="$PWD/run/matrix-verdicts.txt"
# Stays above the tester's 600s wedge watchdog plus boot+settle, so a slow (not
# dead) suite always gets to write its own verdict before the hard kill.
SERVER_TIMEOUT_SECONDS=720
BASE_PORT=25600

VERSIONS="$VERSIONS_DEFAULT"
while [ $# -gt 0 ]; do
    case "$1" in
        --versions) VERSIONS="$(echo "$2" | tr ',' ' ')"; shift 2 ;;
        *) echo "unknown argument: $1" >&2; exit 2 ;;
    esac
done

JDKS_DIR="$HOME/.gradle/jdks"
# Resolve the real java executable for a JDK feature version N. Prefers the
# system JVMs (/usr/libexec/java_home) and falls back to the foojay-provisioned
# toolchains the Gradle integration tasks download under ~/.gradle/jdks — the
# SAME homes launcherFor(N) resolves. The native-JDK flip put 13/14/16/21 on the
# per-entry jdk (each legacy version's newest clean flagless rung); Temurin has
# no arm64 13/14/16, so foojay serves those as x64 builds (run under Rosetta).
# The feature (major) version a JDK home declares, read from its release file
# (JAVA_VERSION="13.0.2+8" -> 13; "1.8.0_492" -> 8). Non-zero if unknown.
jdk_major_of() {
    local home=$1 v
    [ -r "$home/release" ] || return 1
    v=$(sed -n 's/^JAVA_VERSION="\(.*\)"/\1/p' "$home/release" | head -1)
    case "$v" in
        1.*) echo "$v" | cut -d. -f2 ;;   # 1.8.0_492 -> 8
        "")  return 1 ;;
        *)   echo "$v" | cut -d. -f1 ;;   # 13.0.2+8 -> 13
    esac
}
java_home_for() {
    local n=$1 home exe
    # A system JVM — but /usr/libexec/java_home -v N is "N OR NEWER" on macOS, so
    # accept its answer ONLY when it is EXACTLY major N. Otherwise it hands back
    # the newest installed JVM (e.g. -v 13 -> GraalVM 25), which the hard-capped
    # legacy servers refuse ("Unsupported Java detected").
    home="$(/usr/libexec/java_home -v "$n" 2>/dev/null)"
    if [ -n "$home" ] && [ -x "$home/bin/java" ] && [ "$(jdk_major_of "$home")" = "$n" ]; then
        echo "$home/bin/java"; return 0
    fi
    # foojay-provisioned toolchains, named by major: <vendor>-<n>-<arch>-os_x*/
    # jdk-<n>*/Contents/Home (Java 9+); the Java-8 build nests jdk8u*/Contents/Home.
    if [ "$n" = 8 ]; then
        exe=$(ls -d "$JDKS_DIR"/*-8-*/jdk8u*/Contents/Home/bin/java 2>/dev/null | head -1)
    else
        exe=$(ls -d "$JDKS_DIR"/*-"$n"-*/jdk-"$n"*/Contents/Home/bin/java 2>/dev/null | head -1)
    fi
    [ -n "$exe" ] && [ -x "$exe" ] && { echo "$exe"; return 0; }
    return 1
}
# The per-version JDK and bytecodeTier live in the descriptor; this script is
# paper-only, so read the paper entry (26.1.2 also has a folia row).
jdk_for()  { jq -r --arg v "$1" '.entries[] | select(.version==$v and .platform=="paper") | .jdk'          "$MATRIX"; }
tier_for() { jq -r --arg v "$1" '.entries[] | select(.version==$v and .platform=="paper") | .bytecodeTier' "$MATRIX"; }
java_for() { java_home_for "$(jdk_for "$1")"; }

MENTAL_JAR=$(ls "$PWD"/core/build/libs/Mental-*.jar 2>/dev/null | sort -V | tail -1)
TESTER_JAR=$(ls "$PWD"/tester/build/libs/MentalTester-*.jar 2>/dev/null | sort -V | tail -1)
if [ -z "$MENTAL_JAR" ] || [ -z "$TESTER_JAR" ]; then
    echo "plugin jars missing — run ./gradlew build first" >&2
    exit 2
fi

# Pre-flight: every entry's JDK must resolve to a real java BEFORE we launch a
# single server, so a missing toolchain fails loudly here instead of as an
# opaque NO-RESULT per server after minutes of boots.
preflight_versions="$VERSIONS"
for v in $preflight_versions; do
    n=$(jdk_for "$v")
    if [ -z "$n" ] || [ "$n" = null ]; then
        echo "no JDK declared for $v in support-matrix.json" >&2; exit 2
    fi
    if ! java_home_for "$n" >/dev/null; then
        echo "JDK $n (needed by Paper $v) is not installed on this machine." >&2
        echo "Provision it by running any Gradle integration task once (e.g." >&2
        echo "'./gradlew integrationTest'): the foojay toolchain auto-downloads it under" >&2
        echo "~/.gradle/jdks, where this script then finds it (no manual JDK install needed)." >&2
        exit 2
    fi
done

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

# run_one <version> <run-dir> <port> <label>
run_one() {
    local version=$1 dir=$2 port=$3 label=$4
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

    mkdir -p "$dir/plugins"
    # Mirror the Gradle doFirst: pristine plugin config trees per run.
    rm -rf "$dir/plugins/Mental"
    rm -f "$result" "$dir/plugins/MentalTester/test-failures.txt"
    echo "eula=true" > "$dir/eula.txt"
    if [ ! -f "$dir/server.properties" ]; then
        printf 'level-type=flat\nonline-mode=false\nspawn-protection=0\nview-distance=4\nsimulation-distance=4\nmotd=Mental integration test\n' \
            > "$dir/server.properties"
    fi

    # Plugin injection = COPY the jars into plugins/. The legacy Paper builds do
    # NOT parse -add-plugin= (joptsimple reads it as clustered short options and
    # help-dumps + exits — spike-proven on 1.13.2 build 657), and copying is the
    # universal form that works across the whole range. Pristine-reset first: drop
    # any Mental/tester jar a prior run (this script OR the Gradle RunServer,
    # which shares run/<v>/) left in plugins/, so exactly this run's set loads.
    rm -f "$dir/plugins/"Mental-*.jar "$dir/plugins/"MentalTester-*.jar
    cp "$MENTAL_JAR" "$TESTER_JAR" "$dir/plugins/"

    # The declared Multi-Release bytecodeTier for this entry — passed to the tester
    # as -Dmental.tester.tier so its boot suite asserts the tree that actually loaded.
    local tier
    tier=$(tier_for "$version")

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
    # Bare 'nogui' token: the legacy joptsimple REJECTS the dashed --nogui form
    # (help-dumps + exits — scout-proven range-wide); bare nogui works everywhere.
    ( cd "$dir" && exec $keepawake "$java" -Xmx768M -Dcom.mojang.eula.agree=true \
            -Ddisable.watchdog=true -Dmental.tester.nonce="$nonce" \
            -Dmental.tester.tier="$tier" \
            -jar "$jar" nogui --port "$port" ) > "$log" 2>&1 &
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

    # D-9 mirror of the Gradle log scan: Bukkit swallows listener-registration
    # failures and per-event handler throws into the console and keeps running,
    # so the tester's verdict cannot see them — a PASS with matches is downgraded
    # to FAIL with the offending lines echoed. ("to Mental" deliberately also
    # matches MentalTester — both jars are ours.) A linkage-error line counts
    # only when its following stack frames name me.vexmc.mental.
    if [ "$verdict" = "PASS" ]; then
        local scan
        scan=$( { grep -E "has failed to register events for class me\.vexmc\.mental\.|Could not pass event .* to Mental" "$log"; \
                  awk '/java\.lang\.(NoSuchFieldError|NoSuchMethodError|NoClassDefFoundError)/ {err=$0; pend=1; next} \
                       pend && /^[[:space:]]*at / { if (index($0, "me.vexmc.mental") > 0) {print err; pend=0}; next } \
                       {pend=0}' "$log"; } 2>/dev/null )
        if [ -n "$scan" ]; then
            verdict="FAIL(log-scan)"
            echo "[$label] D-9 log scan: swallowed listener/linkage errors:" >> "$LIVE"
            printf '%s\n' "$scan" | sort -u | head -8 | sed "s/^/[$label]   /" >> "$LIVE"
        fi
    fi
    echo "[$label] finished: $verdict (log: $log)" >> "$LIVE"
    echo "$label $verdict" >> "$VERDICTS"
}

echo "── concurrent matrix: $VERSIONS ──" | tee -a "$LIVE"

# Staggered launches: server BOOT is the CPU spike (world load, class
# loading); offsetting starts by a few seconds keeps any one server's tick
# thread from starving while the suites themselves overlap freely.
STAGGER_SECONDS=3
port=$BASE_PORT
for v in $VERSIONS; do
    run_one "$v" "$PWD/run/$v" "$port" "$v" &
    PIDS="$PIDS $!"
    port=$((port + 1))
    sleep "$STAGGER_SECONDS"
done

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
