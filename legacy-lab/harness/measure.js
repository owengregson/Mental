'use strict';
/**
 * Legacy knockback ground-truth harness.
 *
 * Connects protocol-level fake CLIENTS to a real server and measures the
 * exact entity_velocity packets the server ships per hit, while the victim
 * client integrates real legacy client physics (so the server's view of
 * positions/onGround evolves exactly as with a real client).
 *
 * usage: node measure.js <version 1.7|1.8.8|1.21.11> <port> <scenario> [gapTicks] [hits] [pingMs]
 * scenarios:
 *   standing      one non-sprint hit on a standing victim
 *   sprint        one sprint hit on a standing victim
 *   combo         sprint + w-tap hits every gapTicks (victim passive flight)
 *   charge-combo  combo while the victim holds sprint-input toward attacker
 *   double-plain         two non-sprint hits, the second at gapTicks (default
 *                        10 — "right as invuln ends"), attacker chases
 *   double-sprint-nowtap sprint hit 1; NO sprint re-arm (the server consumed
 *                        the flag and a W-holding client never resends it),
 *                        so hit 2 lands plain — the no-w-tap reality
 *   double-sprint-wtap   sprint hit 1, stop+start sprint (w-tap), sprint hit 2
 *   double-sprint-fastwtap  like double-sprint-wtap, but the re-arm rides the
 *                        SAME flush as hit 2: STOP, START, ATTACK back-to-back
 *                        mid-tick — the fastest tap a client can wire. The era
 *                        queue applied them in order (hit 2 ships the sprint
 *                        stamp); a tick-frozen sprint read ships hit 2 plain
 *   double-sprint-fastwtap-attackfirst  the fast tap with the click BEFORE the
 *                        re-arm: STOP, ATTACK, START in one flush. The hit is
 *                        thrown while sprint is still broken; the START trails
 *                        it in the same tick and must NOT retro-grant the stamp
 *                        (era same-tick ATTACK-first is plain — bytecode-pinned).
 *                        MUST run with CLIENT_SPRINT_DROP=0 (see Env)
 *   double-sprint-stap   sprint hit 1; the second re-arm via an S-tap — the
 *                        backward key breaks the sprint (STOP + backward bit),
 *                        then S clears and W re-holds (START) before hit 2
 *   blockhit-one-credit  attacker block-hits: sprint hit 1, then raise a
 *                        blockable item (grants ONE sprint-reset credit) — hit 2
 *                        fresh (credit), hit 3 plain (credit spent), release +
 *                        re-raise + hit 4 fresh again. SETUP_CMDS must arm the
 *                        attacker with a blockable item; run CLIENT_SPRINT_DROP=0
 *   gui-reset            sprint hit 1, then a GUI cycle (all-false input +
 *                        close_window, then re-press forward+sprint) re-arms the
 *                        sprint as a STOP→START pair for a fresh hit 2
 *   chain-plain          HITS plain hits every gapTicks, attacker chasing —
 *                        the combo-vertical probe: each hit's wire vy is the
 *                        era machine's verdict on the victim's state then
 *   kb-sword             one standing hit with whatever SETUP_CMDS put in the
 *                        attacker's hand (knockback-enchant extras probe)
 *   crit-plain           attacker rises then attacks mid-descent (fallDistance
 *                        accrued server-side) — crits must NOT change knockback
 *   throw-then-sword     attacker throws the held projectile (snowball/egg),
 *                        then melees GAP ticks after the hit — the 0-damage
 *                        full-knock + difference-rule probe
 *   rod-sword            attacker casts the held rod (bobber hooks victim),
 *                        melees at GAP, melees again at GAP+12 — rod knock +
 *                        no-knock difference window + post-window full knock
 *   blocking-victim      victim holds use_item (sword block) through one hit —
 *                        era blocking takes FULL knockback, reduced damage
 *   double-counter       double-plain, but the victim (given a KB sword by
 *                        SETUP_CMDS) counter-clicks the attacker between hits —
 *                        the attacker-side server 0.6 self-multiply probe
 * Env: SETUP_CMDS="cmd;;cmd" rcon'd after staging ({atk}/{vic} = bot names,
 * {gy} = ground Y as int); RCON_PORT/RCON_PASS (default lab);
 * CLIENT_SPRINT_DROP=0 disables the attacker's post-attack sprint-STOP mirror
 * (see bot.attack) — REQUIRED by the attackfirst and blockhit scenarios, whose
 * flushes must contain exactly the actions they stage.
 * The victim reports TOUCHDOWN points (first ground contact after a knock)
 * and the SETTLE point, both as distances from its staged start, plus the
 * apex height of every knock flight (the "how floaty" number).
 */
// nmp's lpVec3 reader byte-swaps the middle word (reads LE; Mojang writes the
// 32-bit half big-endian — verified against net.minecraft.network.LpVec3
// bytecode). Patch the codec in the require cache before nmp compiles it.
const lpVec3 = require('minecraft-protocol/src/datatypes/lpVec3');
const [readVarIntP] = [require('protodef').types.varint[0]];
lpVec3[0] = function readLpVec3Fixed(buffer, offset) {
  const a = buffer[offset];
  if (a === 0) return { value: { x: 0, y: 0, z: 0 }, size: 1 };
  const b = buffer[offset + 1];
  const c = buffer.readUInt32BE(offset + 2);           // Mojang: writeInt (BE)
  const packed = c * 65536 + b * 256 + a;              // c<<16 | b<<8 | a
  let scale = a & 3; let size = 6;
  if (a & 4) {
    const v = readVarIntP(buffer, offset + 6);
    scale += v.value * 4; size += v.size;
  }
  const unpack = (sh) => {
    const val = Math.min(32766, Math.floor(packed / 2 ** sh) & 32767);
    return (val * 2) / 32766 - 1;
  };
  return { value: { x: unpack(3) * scale, y: unpack(18) * scale, z: unpack(33) * scale }, size };
};
const mc = require('minecraft-protocol');

const [, , VERSION, PORT_S, SCENARIO, GAP_S, HITS_S, PING_S] = process.argv;
const PORT = parseInt(PORT_S, 10);
const GAP = parseInt(GAP_S || '11', 10);
const HITS = parseInt(HITS_S || '4', 10);
const PING_MS = parseInt(PING_S || '0', 10);
const IS_17 = VERSION === '1.7';
const MODERN = !IS_17 && VERSION !== '1.8.8';

const GRAVITY = 0.08, VDRAG = 0.98, AIR = 0.91, GROUND = 0.546;
const WALK_G = 0.1, SPRINT_G = 0.13, WALK_A = 0.02, SPRINT_A = 0.026;

const SPRINT_START = IS_17 ? 4 : 3;
const SPRINT_STOP = IS_17 ? 5 : 4;

function log(...a) { console.log(...a); }

function mkBot(username, opts = {}) {
  const client = mc.createClient({
    host: '127.0.0.1', port: PORT, username, version: VERSION, auth: 'offline',
    hideErrors: false,
  });
  const bot = {
    client, username,
    id: null, pos: null, yaw: 0, pitch: 0, onGround: true,
    vel: { x: 0, y: 0, z: 0 },
    groundY: null,
    physics: false,          // integrate velocity packets + gravity
    input: null,             // {dirFn, sprint} held movement keys
    velocityLog: [],         // {tick, vx, vy, vz}
    touchdowns: [],          // {tick, x, z} first ground contact per flight
    apexes: [],              // max feet-Y per knock flight
    _flightApex: null,
    tick: 0,
    spawned: false,
    _interval: null,
  };

  client.on('login', (p) => { bot.id = p.entityId; });
  client.on('error', (e) => log(`[${username}] ERROR`, e.message));
  client.on('kick_disconnect', (p) => log(`[${username}] KICKED`, JSON.stringify(p)));
  client.on('disconnect', (p) => log(`[${username}] disconnect`, JSON.stringify(p)));

  // Artificial ping: delay play ping responses (modern probe channel).
  if (PING_MS > 0 && MODERN) {
    // nmp auto-responds to keep_alive but not play ping.
    client.on('ping', (p) => setTimeout(() => client.write('pong', { id: p.id }), PING_MS));
  } else if (MODERN) {
    client.on('ping', (p) => client.write('pong', { id: p.id }));
  }

  // Modern servers hold joining players until the client ACKs chunk batches
  // and reports itself loaded; without these every movement packet is ignored.
  if (MODERN) {
    client.on('chunk_batch_finished', () => client.write('chunk_batch_received', { chunksPerTick: 64 }));
    // the server gates every player command on hasClientLoaded — keep
    // asserting loaded until the flag must have taken
    client.on('login', () => {
      let sent = 0;
      const loader = setInterval(() => {
        try { client.write('player_loaded', {}); } catch (e) { log(`[${username}] player_loaded write failed: ${e.message}`); }
        if (++sent >= 5) clearInterval(loader);
      }, 400);
    });
  }

  client.on('position', (p) => {
    // Legacy clientbound y is head position (feet + 1.62) on 1.7 only.
    const feetY = IS_17 ? p.y - 1.62 : p.y;
    bot.pos = { x: p.x, y: feetY, z: p.z };
    bot.yaw = p.yaw; bot.pitch = p.pitch;
    if (bot.groundY === null) bot.groundY = feetY;
    if (MODERN && p.teleportId !== undefined) {
      client.write('teleport_confirm', { teleportId: p.teleportId });
    }
    if (!bot.spawned) { bot.spawned = true; }
  });

  client.on('update_health', (p) => log(`[${username}] t=${bot.tick} HEALTH ${p.health}`));
  if (process.env.WATCH_META) {
    client.on('entity_metadata', (p) => {
      for (const m of p.metadata || []) {
        // slot 0 is the shared flags byte; 0x08 = sprinting
        if (m.key === 0) {
          log(`[${username}] t=${bot.tick} FLAGS id=${p.entityId} value=0x${Number(m.value).toString(16)} sprint=${!!(Number(m.value) & 0x08)}`);
        }
      }
    });
  }
  if (process.env.WATCH_SOUND) {
    // The attack-branch oracle: vanilla plays entity.player.attack.knockback
    // on the sprint-bonus branch and .strong/.weak otherwise — the victim
    // hears exactly which branch the server took, no metadata needed.
    const soundLog = (p, kind) => log(`[${username}] t=${bot.tick} SOUND ${kind} ${JSON.stringify(p.sound)}`);
    client.on('sound_effect', (p) => soundLog(p, 'world'));
    client.on('entity_sound_effect', (p) => soundLog(p, 'entity'));
  }
  client.on('entity_velocity', (p) => {
    log(`[${username}] t=${bot.tick} RAW-VEL id=${p.entityId} (mine=${bot.id}) shorts=(${p.velocity.x}, ${p.velocity.y}, ${p.velocity.z})`);
    if (p.entityId !== bot.id) return;
    // legacy minecraft-data hands over raw shorts; modern pre-divides by 8000
    const scale = MODERN ? 1 : 1 / 8000;
    const v = { x: p.velocity.x * scale, y: p.velocity.y * scale, z: p.velocity.z * scale };
    bot.velocityLog.push({ tick: bot.tick, ...v });
    log(`[${username}] t=${bot.tick} VELOCITY (${v.x.toFixed(4)}, ${v.y.toFixed(4)}, ${v.z.toFixed(4)})`);
    if (bot.physics) {
      // close the previous knock's flight and open this one's apex window
      if (bot._flightApex !== null) bot.apexes.push(bot._flightApex);
      bot._flightApex = bot.pos ? bot.pos.y : null;
      bot.vel = { ...v };
    }
  });

  // Boxer scenarios: the target is a server-side player (not a harness
  // bot) — map player-info names to uuids and catch its entity spawn.
  bot.targetEid = null;
  if (process.env.TARGET_NAME) {
    const uuidName = {};
    client.on('player_info', (p) => {
      for (const e of (p.data || [])) {
        const name = (e.player && e.player.name) || e.name;
        const uuid = e.uuid || e.UUID;
        if (name && uuid) uuidName[String(uuid)] = name;
      }
    });
    const onSpawn = (p) => {
      const uuid = String(p.objectUUID || p.entityUUID || p.playerUUID || '');
      if (uuid && uuidName[uuid] === process.env.TARGET_NAME) {
        bot.targetEid = p.entityId;
        log(`[${username}] target ${process.env.TARGET_NAME} eid=${p.entityId}`);
      }
    };
    client.on('spawn_entity', onSpawn);
    client.on('named_entity_spawn', onSpawn);
  }

  bot.start = () => {
    bot._interval = setInterval(() => {
      if (!bot.spawned) return;
      bot.tick++;
      if (bot.physics) stepPhysics(bot);
      sendPosition(bot);
    }, 50);
  };
  bot.stop = () => { clearInterval(bot._interval); client.end(); };

  bot.setSprint = (on) => {
    bot.sprinting = on;
    if (MODERN) {
      // 1.21.6+ compacted the entity_action enum (sneak moved into
      // player_input): start_sprinting=1, stop_sprinting=2. The old numeric
      // 3/4 land on start/stop_horse_jump — silently ignored off a horse,
      // which is why modern sprint "never engaged". nmp models the field as
      // a protodef mapper, so the write side takes the NAME.
      client.write('entity_action', {
        entityId: bot.id, actionId: on ? 'start_sprinting' : 'stop_sprinting', jumpBoost: 0,
      });
      // the server derives movement impulse (and sprint sustain) from the
      // input-flags packet — hold forward alongside the sprint command
      client.write('player_input', {
        inputs: { forward: on, backward: false, left: false, right: false, jump: false, shift: false, sprint: on },
      });
    } else {
      client.write('entity_action', {
        entityId: bot.id, actionId: on ? SPRINT_START : SPRINT_STOP, jumpBoost: 0,
      });
    }
  };
  bot.attack = (targetId) => {
    // Real clients send the attack BEFORE the swing on every era
    // (Minecraft.clickMouse: attackEntity, then swingItem). The order is
    // load-bearing on modern servers: Paper's ServerPlayer.swing() resets
    // the attack-strength ticker, so a swing-first bot attacks with a
    // ~0.1 meter — vanilla then skips the sprint-knockback and crit
    // branches entirely (entity.player.attack.weak instead of .knockback).
    const packet = { target: targetId, mouse: 1 };
    if (MODERN) { packet.sneaking = false; }
    client.write('use_entity', packet);
    if (IS_17) client.write('arm_animation', { entityId: bot.id, animation: 1 });
    else if (!MODERN) client.write('arm_animation', {});
    else client.write('arm_animation', { hand: 0 });
    // A real client mirrors the attack's sprint drop locally and its state
    // sync sends STOP_SPRINTING next tick — without it, a fast-path server
    // (which cancels Player.attack, the vanilla flag-clear site) would see
    // the bot sprint forever. CLIENT_SPRINT_DROP=0 disables the mirror to
    // probe exactly that server-side behavior.
    if (bot.sprinting && process.env.CLIENT_SPRINT_DROP !== '0') {
      bot.sprinting = false;
      if (MODERN) {
        client.write('entity_action', { entityId: bot.id, actionId: 'stop_sprinting', jumpBoost: 0 });
      } else {
        client.write('entity_action', { entityId: bot.id, actionId: SPRINT_STOP, jumpBoost: 0 });
      }
    }
  };
  bot.teleportStep = (x, z) => { bot.pos.x = x; bot.pos.z = z; };

  bot.holdSlot = (n) => client.write('held_item_slot', { slotId: n });
  bot._seq = 0;
  // "Use the held item" — the era's right-click-air form (block_place with
  // the -1 sentinel position); modern has a dedicated use_item packet.
  bot.useItem = () => {
    if (IS_17) {
      client.write('block_place', {
        location: { x: -1, y: 255, z: -1 }, direction: -1,
        heldItem: { blockId: -1 }, cursorX: 0, cursorY: 0, cursorZ: 0,
      });
    } else if (!MODERN) {
      client.write('block_place', {
        location: { x: -1, y: -1, z: -1 }, direction: -1,
        heldItem: { blockId: -1 }, cursorX: 0, cursorY: 0, cursorZ: 0,
      });
    } else {
      client.write('use_item', {
        hand: 0, sequence: ++bot._seq, rotation: { x: bot.yaw, y: bot.pitch },
      });
    }
  };
  // Drive the modern movement-input lane (PLAYER_INPUT, >=1.21.2) with named
  // booleans — the same 7-bit packet setSprint holds forward+sprint with, but
  // callable with any subset (s-tap's backward bit, the GUI all-false snapshot).
  // Absent below 1.21.2, so a no-op there: the ledger's evidence lane is off and
  // verdicts ride entity_action alone (the era-correct fallback). Movement bits
  // only — this never touches bot.sprinting (that tracks the entity_action flag
  // the attack() auto-STOP mirror keys off).
  bot.inputBits = (flags = {}) => {
    if (!MODERN) return;
    client.write('player_input', {
      inputs: {
        forward: !!flags.forward, backward: !!flags.backward,
        left: !!flags.left, right: !!flags.right,
        jump: !!flags.jump, shift: !!flags.shift, sprint: !!flags.sprint,
      },
    });
  };
  // "Stop using the held item" — lowers a raised sword-block/shield. The digging
  // packet (block_dig) carries status 5 (RELEASE_USE_ITEM) on every era; the
  // server reads only the status, so location/face are sentinels. Modern adds
  // the block-change sequence field. Feeds the ledger's always-on
  // RELEASE_USE_ITEM lane, which drops any held block-hit credit.
  bot.releaseUseItem = () => {
    if (MODERN) {
      client.write('block_dig', {
        status: 5, location: { x: 0, y: 0, z: 0 }, face: 0, sequence: ++bot._seq,
      });
    } else {
      client.write('block_dig', {
        status: 5, location: { x: 0, y: 0, z: 0 }, face: 0,
      });
    }
  };

  return bot;
}

function sendPosition(bot) {
  const p = bot.pos;
  if (!p) return;
  const base = { x: p.x, y: p.y, z: p.z, yaw: bot.yaw, pitch: bot.pitch, onGround: bot.onGround };
  if (IS_17) {
    // 1.7 wire order is X, FEET, HEAD, Z; nmp's field NAMED 'stance' is the
    // second slot (feet) and 'y' the third (head) — verified by decompile.
    bot.client.write('position_look', { ...base, stance: p.y, y: p.y + 1.62 });
  } else if (!MODERN) {
    bot.client.write('position_look', base);
  } else {
    // modern: MovementFlags is a protodef bitflags OBJECT — an integer here
    // silently writes onGround=false and the server models the player as
    // perpetually falling (the watcher then free-falls the ledger vy)
    const { onGround, ...rest } = base;
    bot.client.write('position_look', {
      ...rest,
      flags: { onGround, hasHorizontalCollision: false },
    });
  }
}

/** One legacy client physics tick: input accel -> move -> gravity/drag (pre-move friction). */
function stepPhysics(bot) {
  const wasGround = bot.onGround;
  // input acceleration, exactly the held-key model
  if (bot.input) {
    const dir = bot.input.dirFn();
    if (dir) {
      const accel = wasGround
        ? (bot.input.sprint ? SPRINT_G : WALK_G)
        : (bot.input.sprint ? SPRINT_A : WALK_A);
      bot.vel.x += dir.x * accel;
      bot.vel.z += dir.z * accel;
    }
  }
  // move with ground-plane collision
  let nx = bot.pos.x + bot.vel.x;
  let ny = bot.pos.y + bot.vel.y;
  let nz = bot.pos.z + bot.vel.z;
  let landed = false;
  if (ny <= bot.groundY && bot.vel.y <= 0) { ny = bot.groundY; landed = true; }
  bot.pos = { x: nx, y: ny, z: nz };
  if (landed && !wasGround && bot.velocityLog.length > 0) {
    // the first ground contact after a knock — what "they landed there"
    // reads as to an observer, before the ground slide finishes
    bot.touchdowns.push({ tick: bot.tick, x: nx, z: nz });
  }
  if (landed) bot.vel.y = 0;          // vertical collision zeroes motY before drag
  bot.onGround = landed;
  if (bot._flightApex !== null && bot.pos.y > bot._flightApex) bot._flightApex = bot.pos.y;
  // gravity + drag; horizontal drag from the PRE-move ground state
  bot.vel.y = (bot.vel.y - GRAVITY) * VDRAG;
  const drag = wasGround ? GROUND : AIR;
  bot.vel.x *= drag;
  bot.vel.z *= drag;
}

const sleepTicks = (n) => new Promise((r) => setTimeout(r, n * 50));
const until = async (cond, timeoutMs, what) => {
  const t0 = Date.now();
  while (!cond()) {
    if (Date.now() - t0 > timeoutMs) throw new Error('timeout waiting for ' + what);
    await new Promise((r) => setTimeout(r, 50));
  }
};

/** Minimal rcon client for staging commands (gear, effects, terrain). */
function rconSend(port, pass, commands) {
  const net = require('net');
  return new Promise((resolve, reject) => {
    const frame = (id, type, body) => {
      const payload = Buffer.concat([Buffer.from(body, 'utf8'), Buffer.from([0, 0])]);
      const buf = Buffer.alloc(12 + payload.length);
      buf.writeInt32LE(8 + payload.length, 0);
      buf.writeInt32LE(id, 4);
      buf.writeInt32LE(type, 8);
      payload.copy(buf, 12);
      return buf;
    };
    const sock = net.connect(port, '127.0.0.1');
    let buffer = Buffer.alloc(0);
    let queue = [...commands];
    let authed = false;
    const bodies = [];
    const fail = (e) => { sock.destroy(); reject(e); };
    sock.on('error', fail);
    sock.on('connect', () => sock.write(frame(1, 3, pass)));
    sock.on('data', (d) => {
      buffer = Buffer.concat([buffer, d]);
      while (buffer.length >= 4) {
        const len = buffer.readInt32LE(0);
        if (buffer.length < 4 + len) return;
        const id = buffer.readInt32LE(4);
        const body = buffer.slice(12, 4 + len - 2).toString('utf8');
        buffer = buffer.slice(4 + len);
        if (!authed) {
          if (id === -1) return fail(new Error('rcon auth failed'));
          authed = true;
        } else if (body.trim()) {
          bodies.push(body.trim());
          log(`# rcon> ${body.trim().slice(0, 120)}`);
        }
        if (queue.length === 0) { sock.end(); return resolve(bodies); }
        sock.write(frame(2, 2, queue.shift()));
      }
    });
  });
}

/**
 * Cross-validation against SimpleBoxer: 'attack-boxer' has this REAL
 * protocol client melee a boxer (its ATTACK runs the full netty fast path;
 * the boxer's received wire + settled flight are the measurements);
 * 'boxer-attacks' stands passive while an aimbot boxer works us over and
 * records the velocity packets a real client receives from boxer hits.
 */
async function runBoxerScenario(attacker) {
  const RPORT = parseInt(process.env.RCON_PORT, 10);
  const RPASS = process.env.RCON_PASS || 'lab';
  const NAME = process.env.TARGET_NAME || 'Sparring';
  const rcon = (cmds) => rconSend(RPORT, RPASS, cmds);
  const posOf = async () => {
    const bodies = await rcon([`data get entity ${NAME} Pos`]);
    const m = /\[(-?[\d.]+)d?, (-?[\d.]+)d?, (-?[\d.]+)d?\]/.exec(bodies.join(' '));
    return m ? { x: +m[1], y: +m[2], z: +m[3] } : null;
  };
  attacker.yaw = 0; attacker.pitch = 0;
  await sleepTicks(40);
  const ax = attacker.pos.x, ay = attacker.groundY, az = attacker.pos.z;
  const ACTIVE = SCENARIO === 'attack-boxer-active';
  // attack-boxer-active spawns with NO preset token — the plugin's config
  // defaults (rush + sprint, w-tap false, 0 ping), the exact bot a bare
  // `/boxer spawn <name>` gives a sparring owner.
  const preset = SCENARIO === 'boxer-attacks' ? 'aimbot' : ACTIVE ? '' : 'dummy';
  // SPAWN_OFFSET widens the staging gap (an active boxer rushes the rest) —
  // a long pure-approach window isolates command-driven sprint flag changes
  // from the attack-proc clears that start at contact.
  const offset = SCENARIO === 'boxer-attacks' ? 2.8
      : ACTIVE ? parseFloat(process.env.SPAWN_OFFSET || '3.0') : 3.0;
  // Pave the knock lane: world-spawn terrain (village paths, dips) costs
  // exact settles — same flat-stone discipline as the era scenarios.
  const gy = Math.floor(ay) - 1;
  const bx = Math.floor(ax), bz = Math.floor(az);
  await rcon([
    `fill ${bx - 2} ${gy} ${bz - 2} ${bx + 2} ${gy} ${bz + 16} stone`,
    `fill ${bx - 2} ${gy + 1} ${bz - 2} ${bx + 2} ${gy + 3} ${bz + 16} air`,
    `boxer remove ${NAME}`,
    `boxer spawn ${NAME} ${preset} ${ACTIVE ? `target:${attacker.username} ` : ''}at ${ax.toFixed(2)} ${ay.toFixed(2)} ${(az + offset).toFixed(2)}`.replace(/\s+/g, ' '),
  ]);
  await sleepTicks(30);
  if (SCENARIO === 'attack-boxer') {
    await until(() => attacker.targetEid !== null, 10000, 'boxer entity spawn');
    const before = await posOf();
    log(`# boxer pre-hit at (${before.x.toFixed(3)}, ${before.y.toFixed(3)}, ${before.z.toFixed(3)})`);
    attacker.holdSlot(0);
    await sleepTicks(2);
    for (let h = 0; h < HITS; h++) {
      attacker.attack(attacker.targetEid);
      log(`# hit ${h + 1} thrown at attacker tick ${attacker.tick}`);
      await sleepTicks(GAP);
    }
    await sleepTicks(60);
    const after = await posOf();
    log(`# boxer settled at (${after.x.toFixed(3)}, ${after.y.toFixed(3)}, ${after.z.toFixed(3)})`);
    log(`# SETTLE dx=${(after.x - before.x).toFixed(3)} dz=${(after.z - before.z).toFixed(3)} d=${Math.hypot(after.x - before.x, after.z - before.z).toFixed(3)}`);
  } else if (ACTIVE) {
    // The live sparring shape: the config-default boxer charges the
    // attacker while sprint+w-tap hits land on the era cadence — the
    // 'charge-combo' control re-run against a boxer instead of a protocol
    // client. The boxer's distance-from-attacker trail is the measurement:
    // how far each hit actually moves a victim that counter-holds W with
    // machine discipline, and how fast it re-closes. The attacker's own
    // velocityLog doubles as the return-fire probe — every stamp the
    // wtap-false boxer's punches carry comes back in RECV lines.
    await until(() => attacker.targetEid !== null, 10000, 'boxer entity spawn');
    attacker.holdSlot(0);
    await sleepTicks(2);
    const distNow = async () => {
      const p = await posOf();
      return p ? Math.hypot(p.x - attacker.pos.x, p.z - attacker.pos.z) : Infinity;
    };
    // Let the rush close in before the first swing.
    for (let t = 0; t < 100 && (await distNow()) > 2.8; t++) {
      await sleepTicks(1);
    }
    log(`# boxer in the pocket at attacker tick ${attacker.tick}`);
    attacker.setSprint(true);
    await sleepTicks(2);
    for (let h = 0; h < HITS; h++) {
      // wait (bounded) for the boxer to be back inside reach — the trade
      // rhythm: nobody swings at a target three blocks out
      for (let t = 0; t < 40 && (await distNow()) > 2.9; t++) {
        await sleepTicks(1);
      }
      attacker.setSprint(false);
      attacker.setSprint(true);          // the w-tap re-arm
      attacker.attack(attacker.targetEid);
      const atHit = await posOf();
      log(`# hit ${h + 1} thrown at attacker tick ${attacker.tick}, boxer at d=${Math.hypot(atHit.x - attacker.pos.x, atHit.z - attacker.pos.z).toFixed(3)}`);
      // trail the knock window: distance from the attacker every 2 ticks
      let maxD = 0;
      for (let t = 0; t < GAP; t += 2) {
        await sleepTicks(2);
        const d = await distNow();
        if (d !== Infinity && d > maxD) maxD = d;
        log(`#   trail t=+${t + 2} d=${d.toFixed(3)}`);
      }
      log(`# hit ${h + 1} window max distance ${maxD.toFixed(3)}`);
    }
    await sleepTicks(20);
    log(`# return fire received: ${attacker.velocityLog.length} velocity packets`);
    for (const v of attacker.velocityLog.slice(0, 16)) {
      log(`# RECV t=${v.tick} (${v.x.toFixed(4)}, ${v.y.toFixed(4)}, ${v.z.toFixed(4)})`);
    }
  } else {
    attacker.physics = true; // a real victim: knocks fly us, we report positions
    await rcon([`boxer target ${NAME} ${attacker.username}`]);
    await sleepTicks(200);
    log(`# received ${attacker.velocityLog.length} velocity packets from boxer hits`);
    for (const v of attacker.velocityLog.slice(0, 12)) {
      log(`# RECV t=${v.tick} (${v.x.toFixed(4)}, ${v.y.toFixed(4)}, ${v.z.toFixed(4)})`);
    }
  }
  await rcon([`boxer remove ${NAME}`]);
}

async function main() {
  const stamp = Date.now() % 100000;
  // JOIN=victim-first flips CONNECTION order only (roles unchanged). On
  // 1.7.10 the network phase iterates connections in join order and player
  // physics rides the player's own position-packet slot, so whether the
  // victim's physics tick lands between the hit and the tracker send — i.e.
  // whether the wire ships decayed — may depend on exactly this order.
  const VICTIM_FIRST = process.env.JOIN === 'victim-first';
  const spawnBot = async (name, what) => {
    const bot = mkBot(name);
    await until(() => bot.spawned && bot.id !== null, 15000, what + ' spawn');
    bot.start();
    await sleepTicks(10);
    return bot;
  };
  if (SCENARIO === 'attack-boxer' || SCENARIO === 'attack-boxer-active'
      || SCENARIO === 'boxer-attacks') {
    const attacker = await spawnBot(`atk${stamp}`, 'attacker');
    await runBoxerScenario(attacker);
    log('# scenario complete');
    attacker.stop();
    setTimeout(() => process.exit(0), 300);
    return;
  }

  let attacker, victim;
  if (VICTIM_FIRST) {
    victim = await spawnBot(`vic${stamp}`, 'victim');
    attacker = await spawnBot(`atk${stamp}`, 'attacker');
  } else {
    attacker = await spawnBot(`atk${stamp}`, 'attacker');
    victim = await spawnBot(`vic${stamp}`, 'victim');
  }
  log(`# join order: ${VICTIM_FIRST ? 'victim-first' : 'attacker-first'}`);

  // Place: attacker at his spawn spot; victim 3 blocks +Z from him.
  const ax = attacker.pos.x, az = attacker.pos.z;
  attacker.yaw = 0; attacker.pitch = 0;            // yaw 0 faces +Z
  const steps = 8;
  for (let i = 1; i <= steps; i++) {
    victim.pos.x = victim.pos.x + (ax - victim.pos.x) / (steps - i + 1);
    victim.pos.z = victim.pos.z + (az + 3.0 - victim.pos.z) / (steps - i + 1);
    await sleepTicks(1);
  }
  victim.pos.x = ax; victim.pos.z = az + 3.0; victim.pos.y = victim.groundY;
  victim.yaw = 180;                                 // face the attacker
  // settle + outlive the 60-tick join invulnerability (EntityPlayerMP)
  await sleepTicks(75);

  // Staging commands (gear, effects, terrain) — rcon'd in before the
  // scenario so the servers stay vanilla-configured between runs.
  if (process.env.SETUP_CMDS) {
    const cmds = process.env.SETUP_CMDS.split(';;').flatMap((raw) => {
      const c = raw
        .replaceAll('{atk}', attacker.username)
        .replaceAll('{vic}', victim.username)
        .replaceAll('{gy}', String(Math.floor(victim.groundY)))
        .replaceAll('{vx}', String(Math.floor(victim.pos.x)))
        .replaceAll('{vz}', String(Math.floor(victim.pos.z)));
      // LANE:<block> — pave the victim's knock path (straight +Z) so the
      // block-under-feet slipperiness governs the whole flight.
      if (c.startsWith('LANE:')) {
        const block = c.slice(5);
        const y = Math.floor(victim.groundY) - 1;
        const out = [];
        for (let dz = -2; dz <= 14; dz++) {
          for (let dx = -1; dx <= 1; dx++) {
            out.push(`setblock ${Math.floor(victim.pos.x) + dx} ${y} ${Math.floor(victim.pos.z) + dz} ${block}`);
          }
        }
        return out;
      }
      return [c];
    });
    await rconSend(
      parseInt(process.env.RCON_PORT, 10), process.env.RCON_PASS || 'lab', cmds);
    await sleepTicks(10);
  }

  // The victim's entity id as seen by the attacker is the global id.
  const targetId = victim.id;
  log(`# scenario=${SCENARIO} version=${VERSION} gap=${GAP} hits=${HITS}`);
  log(`# attacker at (${attacker.pos.x.toFixed(2)}, ${attacker.pos.y.toFixed(2)}, ${attacker.pos.z.toFixed(2)})`);
  log(`# victim   at (${victim.pos.x.toFixed(2)}, ${victim.pos.y.toFixed(2)}, ${victim.pos.z.toFixed(2)})`);

  victim.physics = true;                            // from here the victim is a real client
  victim.tick = 0;

  const chase = (cap = 0.28) => {
    // keep the attacker within reach: walk straight toward the victim's
    // current spot, capped at sprint speed per tick (the double scenarios
    // pass a higher cap — a sprint-knocked victim outruns 0.28/tick from
    // 3 blocks behind, and the staging only needs the attacker BEHIND the
    // victim and in server reach at the hit), stop 2.5 blocks short
    const dx = victim.pos.x - attacker.pos.x;
    const dz = victim.pos.z - attacker.pos.z;
    const d = Math.hypot(dx, dz);
    const want = d - 2.5;
    if (want > 0.01) {
      const step = Math.min(cap, want);
      attacker.pos.x += (dx / d) * step;
      attacker.pos.z += (dz / d) * step;
    }
  };

  if (SCENARIO === 'standing' || SCENARIO === 'kb-sword') {
    // kb-sword differs only by what SETUP_CMDS put in the attacker's hand.
    attacker.holdSlot(0);
    await sleepTicks(2);
    attacker.attack(targetId);
    await sleepTicks(30);
  } else if (SCENARIO === 'crit-plain') {
    // Rise airborne, then attack mid-descent: the handler's move replication
    // accrues fallDistance from the descending packets, so the server takes
    // the crit branch (1.5× damage) — knockback must be UNCHANGED.
    attacker.onGround = false;
    for (const dy of [0.42, 0.33, -0.2, -0.3]) {
      attacker.pos.y += dy;
      await sleepTicks(1);
    }
    attacker.attack(targetId);
    await sleepTicks(2);
    attacker.pos.y = attacker.groundY;
    attacker.onGround = true;
    await sleepTicks(30);
  } else if (SCENARIO === 'throw-then-sword' || SCENARIO === 'rod-sword') {
    // The 0-damage knock probe: the projectile/bobber knocks at full
    // strength AND arms the whole 20-tick hurt window, so a melee inside
    // the half-window deals difference damage with NO knockback; a melee
    // after it knocks in full.
    attacker.holdSlot(0);
    attacker.pitch = SCENARIO === 'rod-sword' ? 8 : 2; // drop onto the torso
    await sleepTicks(2);
    attacker.useItem();
    log(`# projectile cast at victim tick ${victim.tick}`);
    await until(() => victim.velocityLog.length >= 1, 5000, 'projectile knock');
    const hitTick = victim.tick;
    attacker.pitch = 0;
    log(`# projectile knock at victim tick ${hitTick}`);
    while (victim.tick < hitTick + GAP) { chase(0.5); await sleepTicks(1); }
    attacker.attack(targetId);
    log(`# sword 1 thrown at victim tick ${victim.tick} (mid-window: expect damage, NO velocity)`);
    for (let t = 0; t < 12; t++) { chase(0.5); await sleepTicks(1); }
    attacker.attack(targetId);
    log(`# sword 2 thrown at victim tick ${victim.tick} (post-window: expect full velocity)`);
    await sleepTicks(40);
  } else if (SCENARIO === 'weak-strong') {
    // The difference rule, pure melee: a fist hit arms the window at
    // lastDamage 1; a sword hit GAP ticks later out-damages it and deals
    // the difference with NO knockback (era: fullHit=false skips knockBack,
    // the flinch, and the sound). Expect exactly ONE velocity packet.
    attacker.holdSlot(8); // empty hand
    await sleepTicks(2);
    attacker.attack(targetId);
    log(`# fist thrown at victim tick ${victim.tick}`);
    for (let t = 0; t < GAP; t++) { chase(0.5); await sleepTicks(1); }
    attacker.holdSlot(0); // the SETUP_CMDS sword
    await sleepTicks(1);
    attacker.attack(targetId);
    log(`# sword thrown at victim tick ${victim.tick} (mid-window: expect damage, NO velocity)`);
    await sleepTicks(40);
  } else if (SCENARIO === 'blocking-victim') {
    // Era sword-blocking halves damage AFTER knockBack already ran: the
    // victim must take FULL knockback and reduced damage.
    victim.holdSlot(0);
    await sleepTicks(2);
    victim.useItem();
    await sleepTicks(4);
    attacker.attack(targetId);
    log(`# hit on blocking victim at tick ${victim.tick}`);
    await sleepTicks(30);
  } else if (SCENARIO === 'blockhit-one-credit') {
    // The always-on block-hit reset door, ONE credit per raise: a sprinting
    // attacker who right-clicks a blockable item (shield / decorated sword)
    // earns a single sprint-reset credit — the next melee ships the fresh
    // sprint knock and spends it; a second melee while still blocking is plain;
    // releasing and re-raising earns a fresh credit. The door's eligibility gate
    // is clientSprinting (or recent sprint-with-key), so this MUST run with
    // CLIENT_SPRINT_DROP=0 to keep the raw client sprint latched across the
    // sequence. SETUP_CMDS must arm the attacker with a blockable item in the
    // main hand (a shield, or a decorated sword with SWORD_BLOCKING enabled).
    attacker.holdSlot(0);
    await sleepTicks(2);
    attacker.setSprint(true);
    await sleepTicks(2);
    for (let i = 0; i < 4; i++) { attacker.pos.z += 0.28; await sleepTicks(1); }
    attacker.attack(targetId);
    log(`# blockhit hit 1 (sprint engagement) at victim tick ${victim.tick} -- expect fresh`);
    for (let t = 0; t < GAP; t++) { chase(0.5); await sleepTicks(1); }
    attacker.useItem();                        // raise the block -> grant 1 credit
    log(`# blockhit block raised (useItem) at victim tick ${victim.tick}`);
    await sleepTicks(2);
    attacker.attack(targetId);
    log(`# blockhit hit 2 (block credit) at victim tick ${victim.tick} -- expect fresh`);
    for (let t = 0; t < GAP; t++) { chase(0.5); await sleepTicks(1); }
    attacker.attack(targetId);                 // credit already spent -> plain
    log(`# blockhit hit 3 (credit spent) at victim tick ${victim.tick} -- expect plain`);
    for (let t = 0; t < GAP; t++) { chase(0.5); await sleepTicks(1); }
    attacker.releaseUseItem();                 // drop the block -> drop any credit
    log(`# blockhit block released at victim tick ${victim.tick}`);
    await sleepTicks(2);
    attacker.useItem();                        // re-raise -> a fresh credit
    log(`# blockhit block re-raised (useItem) at victim tick ${victim.tick}`);
    await sleepTicks(2);
    attacker.attack(targetId);
    log(`# blockhit hit 4 (re-credit) at victim tick ${victim.tick} -- expect fresh`);
    await sleepTicks(45);
  } else if (SCENARIO === 'gui-reset') {
    // The GUI reset cycle: opening a container stops the client streaming
    // movement (all input bits drop) and drops the sprint (a STOP crosses);
    // closing it re-engages forward+sprint (a START crosses) — a full
    // STOP->START pair, era-pinned to re-arm the sprint knock (spec §0). GUI
    // *open* has no serverbound packet; its remote signature is the all-false
    // input snapshot plus the STOP that accompanies it. close_window (windowId
    // 0, the player inventory) is the cycle's one serverbound marker.
    attacker.holdSlot(0);
    await sleepTicks(2);
    attacker.setSprint(true);
    await sleepTicks(2);
    for (let i = 0; i < 4; i++) { attacker.pos.z += 0.28; await sleepTicks(1); }
    attacker.attack(targetId);
    log(`# gui-reset hit 1 (sprint engagement) at victim tick ${victim.tick} -- expect fresh`);
    for (let t = 0; t < GAP; t++) { chase(0.5); await sleepTicks(1); }
    attacker.inputBits({});                    // GUI open: all input bits drop
    attacker.setSprint(false);                 // sprint drops while open -> STOP
    attacker.client.write('close_window', { windowId: 0 });
    log(`# gui-reset GUI cycle (all-false input + close_window) at victim tick ${victim.tick}`);
    await sleepTicks(4);
    attacker.inputBits({ forward: true, sprint: true }); // re-press forward+sprint
    attacker.setSprint(true);                  // re-engage -> START
    log(`# gui-reset re-engaged (forward+sprint) at victim tick ${victim.tick}`);
    for (let t = 0; t < 4; t++) { chase(0.5); await sleepTicks(1); }
    attacker.attack(targetId);
    log(`# gui-reset hit 2 (post-GUI reset) at victim tick ${victim.tick} -- expect fresh`);
    await sleepTicks(45);
  } else if (SCENARIO === 'double-counter') {
    // double-plain, but the victim counter-clicks the attacker between the
    // hits holding a KB sword (kbLevels > 0): the era server multiplies the
    // VICTIM'S OWN motion fields ×0.6 on their swing, so on 1.7.10 hit 2's
    // residual term shrinks vs the double-plain control.
    victim.holdSlot(0);
    await sleepTicks(2);
    attacker.attack(targetId);
    log(`# hit 1 thrown at victim tick ${victim.tick}`);
    for (let t = 0; t < GAP; t++) {
      if (t === 4) {
        victim.attack(attacker.id);
        log(`# victim counter-click at tick ${victim.tick}`);
      }
      chase(0.5);
      await sleepTicks(1);
    }
    attacker.attack(targetId);
    log(`# hit 2 thrown at victim tick ${victim.tick}`);
    await sleepTicks(45);
  } else if (SCENARIO.startsWith('double')) {
    // The canon two-hit cases: second hit thrown "right as the invuln
    // window ends" (gap defaults to 10 ticks), the attacker chasing to
    // stay in reach exactly like a real player following a knock.
    const sprintFirst = SCENARIO !== 'double-plain';
    const wtap = SCENARIO === 'double-sprint-wtap';
    const fastWtap = SCENARIO === 'double-sprint-fastwtap';
    const attackFirst = SCENARIO === 'double-sprint-fastwtap-attackfirst';
    const stap = SCENARIO === 'double-sprint-stap';
    if (sprintFirst) {
      attacker.setSprint(true);
      await sleepTicks(2);
      for (let i = 0; i < 4; i++) { attacker.pos.z += 0.28; await sleepTicks(1); }
    }
    attacker.attack(targetId);
    log(`# hit 1 thrown at victim tick ${victim.tick}`);
    for (let t = 0; t < GAP; t++) {
      // A real player w-taps DURING the chase; modern servers only sustain
      // a sprint toggle backed by movement impulse (1.8.9 is pure flag),
      // so the re-arm happens mid-stride, a few ticks before hit 2.
      if (wtap && t === GAP - 4) {
        attacker.setSprint(false);
        attacker.setSprint(true);
      }
      // The s-tap rides the same mid-chase timing, expressed through the
      // movement-input lane: the backward key breaks a forward sprint (you
      // cannot sprint backward), so STOP crosses with the backward bit as
      // the input evidence; three ticks later S clears, W re-holds, and
      // START crosses — a full STOP->START pair whose remote signature (the
      // backward-bit snapshot) distinguishes it from a w-tap.
      if (stap && t === GAP - 7) {
        attacker.setSprint(false);               // sprint breaks -> STOP crosses
        attacker.inputBits({ backward: true });  // S held (the backward bit)
        log(`# s-tap: backward held, sprint broken at victim tick ${victim.tick}`);
      }
      if (stap && t === GAP - 4) {
        attacker.inputBits({ forward: true, sprint: true }); // S clears, W re-holds
        attacker.setSprint(true);                // sprint re-armed -> START crosses
        log(`# s-tap: backward cleared, sprint re-armed at victim tick ${victim.tick}`);
      }
      chase(0.5);
      await sleepTicks(1);
    }
    if (fastWtap) {
      // The fastest tap a client can wire: release and re-press decided in
      // one client tick, the click in the same flush — STOP, START, ATTACK
      // arrive back-to-back mid-server-tick, with no boundary between the
      // re-arm and the hit for a tick-frozen read to hide behind.
      attacker.setSprint(false);
      attacker.setSprint(true);
      log(`# fast w-tap wired in hit 2's flush at victim tick ${victim.tick}`);
    }
    if (attackFirst) {
      // Fast-wtap's flush with the click BEFORE the re-arm: STOP, ATTACK, START
      // in one flush. The STOP de-arms the wire; the shared attack below fires
      // while sprint is broken (era-plain); the START trails it in the same tick
      // and must NOT retro-grant the sprint stamp (bytecode-pinned same-tick
      // ATTACK-first). MUST run with CLIENT_SPRINT_DROP=0 so the attacker's own
      // auto-STOP mirror (fired after every sprint hit) never stamps an extra
      // stop_sprinting into the trail — the flush the verdict reads is exactly
      // STOP, ATTACK, START.
      attacker.setSprint(false);               // STOP
      log(`# attack-first: sprint broken (STOP) before hit 2 at victim tick ${victim.tick}`);
    }
    attacker.attack(targetId);
    log(`# hit 2 thrown at victim tick ${victim.tick}, dist=${Math.hypot(victim.pos.x - attacker.pos.x, victim.pos.z - attacker.pos.z).toFixed(2)}`);
    if (attackFirst) {
      attacker.setSprint(true);                // START trails the ATTACK
      log(`# attack-first: START trailed hit 2 in the same tick at victim tick ${victim.tick}`);
    }
    await sleepTicks(45);
  } else if (SCENARIO === 'chain-plain') {
    // The combo-vertical probe: plain hits isolate the base vertical (no
    // sprint extra), the gap models a spam-clicking attacker (hits register
    // the tick the hurt window halves: 10 on vanilla 20, 9 on OCM's 18).
    for (let h = 0; h < HITS; h++) {
      attacker.attack(targetId);
      log(`# hit ${h + 1} thrown at victim tick ${victim.tick}, dist=${Math.hypot(victim.pos.x - attacker.pos.x, victim.pos.z - attacker.pos.z).toFixed(2)}`);
      if (h < HITS - 1) { for (let t = 0; t < GAP; t++) { chase(0.5); await sleepTicks(1); } }
    }
    await sleepTicks(45);
  } else if (SCENARIO === 'sprint') {
    attacker.setSprint(true);
    await sleepTicks(2);
    // modern servers engage sprint only for genuinely moving players —
    // run one block forward at sprint speed before swinging
    for (let i = 0; i < 4; i++) { attacker.pos.z += 0.28; await sleepTicks(1); }
    attacker.attack(targetId);
    await sleepTicks(30);
  } else if (SCENARIO === 'combo' || SCENARIO === 'charge-combo') {
    if (SCENARIO === 'charge-combo') {
      victim.input = {
        sprint: true,
        dirFn: () => {
          const dx = attacker.pos.x - victim.pos.x;
          const dz = attacker.pos.z - victim.pos.z;
          const d = Math.hypot(dx, dz);
          return d < 0.3 ? null : { x: dx / d, z: dz / d };
        },
      };
      victim.setSprint(true);
    }
    attacker.setSprint(true);
    await sleepTicks(3);
    if (SCENARIO === 'charge-combo') {
      // Throw hit 1 at reach-entry like a real first trade hit — a fixed
      // ramp lets the charging victim run PAST the attacker, where the base
      // push (away from attacker) and the sprint extra (along attacker yaw)
      // oppose and the first knock measures ~0.05 instead of ~0.9.
      for (let t = 0; t < 40; t++) {
        const d = Math.hypot(victim.pos.x - attacker.pos.x, victim.pos.z - attacker.pos.z);
        if (d <= 2.8) break;
        await sleepTicks(1);
      }
    }
    for (let h = 0; h < HITS; h++) {
      attacker.attack(targetId);
      log(`# hit ${h + 1} thrown at victim tick ${victim.tick}, dist=${Math.hypot(victim.pos.x - attacker.pos.x, victim.pos.z - attacker.pos.z).toFixed(2)}`);
      for (let t = 0; t < GAP; t++) { chase(); await sleepTicks(1); }
      // w-tap: re-arm sprint between hits
      attacker.setSprint(false);
      attacker.setSprint(true);
    }
    await sleepTicks(40);
  } else {
    throw new Error('unknown scenario ' + SCENARIO);
  }

  // Trajectory summary — distances from the victim's staged start, both
  // the touchdown points (first ground contact per flight: what an
  // observer eyeballs as "they landed there") and the settled endpoint.
  const startX = ax, startZ = az + 3.0;
  const dist = (x, z) => Math.hypot(x - startX, z - startZ);
  log(`# victim final (${victim.pos.x.toFixed(3)}, ${victim.pos.y.toFixed(3)}, ${victim.pos.z.toFixed(3)})`);
  log(`# settle distance from start: ${dist(victim.pos.x, victim.pos.z).toFixed(3)} blocks`);
  victim.touchdowns.forEach((t, i) =>
    log(`#   touchdown${i + 1} t=${t.tick} at ${dist(t.x, t.z).toFixed(3)} blocks`));
  log(`# velocity packets received by victim: ${victim.velocityLog.length}`);
  victim.velocityLog.forEach((v, i) =>
    log(`#   hit${i + 1} t=${v.tick} (${v.x.toFixed(4)}, ${v.y.toFixed(4)}, ${v.z.toFixed(4)})`));
  if (victim._flightApex !== null) victim.apexes.push(victim._flightApex);
  log(`# flight apexes above ground: ${victim.apexes
    .map((a) => (a - victim.groundY).toFixed(3)).join(', ') || 'none'}`);

  attacker.stop(); victim.stop();
  process.exit(0);
}

main().catch((e) => { console.error(e); process.exit(1); });
