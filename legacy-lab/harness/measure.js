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
        if (m.key === 0) log(`[${username}] t=${bot.tick} META id=${p.entityId} flags=0x${(m.value & 0xff).toString(16)} sprint=${!!(m.value & 0x08)}`);
      }
    });
  }
  client.on('entity_velocity', (p) => {
    log(`[${username}] t=${bot.tick} RAW-VEL id=${p.entityId} (mine=${bot.id}) shorts=(${p.velocity.x}, ${p.velocity.y}, ${p.velocity.z})`);
    if (p.entityId !== bot.id) return;
    // legacy minecraft-data hands over raw shorts; modern pre-divides by 8000
    const scale = MODERN ? 1 : 1 / 8000;
    const v = { x: p.velocity.x * scale, y: p.velocity.y * scale, z: p.velocity.z * scale };
    bot.velocityLog.push({ tick: bot.tick, ...v });
    log(`[${username}] t=${bot.tick} VELOCITY (${v.x.toFixed(4)}, ${v.y.toFixed(4)}, ${v.z.toFixed(4)})`);
    if (bot.physics) { bot.vel = { ...v }; }
  });

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
    client.write('entity_action', {
      entityId: bot.id, actionId: on ? SPRINT_START : SPRINT_STOP, jumpBoost: 0,
    });
    if (MODERN) {
      // 1.21.4+ servers derive sprint intent from the input-flags packet
      client.write('player_input', {
        inputs: { forward: on, backward: false, left: false, right: false, jump: false, shift: false, sprint: on },
      });
    }
  };
  bot.attack = (targetId) => {
    if (IS_17) client.write('arm_animation', { entityId: bot.id, animation: 1 });
    else if (!MODERN) client.write('arm_animation', {});
    else client.write('arm_animation', { hand: 0 });
    const packet = { target: targetId, mouse: 1 };
    if (MODERN) { packet.sneaking = false; }
    client.write('use_entity', packet);
  };
  bot.teleportStep = (x, z) => { bot.pos.x = x; bot.pos.z = z; };

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
  if (landed) bot.vel.y = 0;          // vertical collision zeroes motY before drag
  bot.onGround = landed;
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

async function main() {
  const stamp = Date.now() % 100000;
  const attacker = mkBot(`atk${stamp}`);
  await until(() => attacker.spawned && attacker.id !== null, 15000, 'attacker spawn');
  attacker.start();
  await sleepTicks(10);

  const victim = mkBot(`vic${stamp}`);
  await until(() => victim.spawned && victim.id !== null, 15000, 'victim spawn');
  victim.start();
  await sleepTicks(10);

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

  // The victim's entity id as seen by the attacker is the global id.
  const targetId = victim.id;
  log(`# scenario=${SCENARIO} version=${VERSION} gap=${GAP} hits=${HITS}`);
  log(`# attacker at (${attacker.pos.x.toFixed(2)}, ${attacker.pos.y.toFixed(2)}, ${attacker.pos.z.toFixed(2)})`);
  log(`# victim   at (${victim.pos.x.toFixed(2)}, ${victim.pos.y.toFixed(2)}, ${victim.pos.z.toFixed(2)})`);

  victim.physics = true;                            // from here the victim is a real client
  victim.tick = 0;

  const chase = () => {
    // keep the attacker within reach: walk straight toward the victim's
    // current spot, capped at sprint speed per tick, stop 2.5 blocks short
    const dx = victim.pos.x - attacker.pos.x;
    const dz = victim.pos.z - attacker.pos.z;
    const d = Math.hypot(dx, dz);
    const want = d - 2.5;
    if (want > 0.01) {
      const step = Math.min(0.28, want);
      attacker.pos.x += (dx / d) * step;
      attacker.pos.z += (dz / d) * step;
    }
  };

  if (SCENARIO === 'standing') {
    attacker.attack(targetId);
    await sleepTicks(30);
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
      // run to terminal sprint speed before the first hit
      await sleepTicks(15);
    }
    attacker.setSprint(true);
    await sleepTicks(3);
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

  // Trajectory summary
  const start = { x: attacker.pos.x, z: az + 3.0 };
  log(`# victim final (${victim.pos.x.toFixed(3)}, ${victim.pos.y.toFixed(3)}, ${victim.pos.z.toFixed(3)})`);
  log(`# velocity packets received by victim: ${victim.velocityLog.length}`);
  victim.velocityLog.forEach((v, i) =>
    log(`#   hit${i + 1} t=${v.tick} (${v.x.toFixed(4)}, ${v.y.toFixed(4)}, ${v.z.toFixed(4)})`));

  attacker.stop(); victim.stop();
  process.exit(0);
}

main().catch((e) => { console.error(e); process.exit(1); });
