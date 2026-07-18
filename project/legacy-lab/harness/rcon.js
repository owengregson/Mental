'use strict';
// minimal rcon: node rcon.js <port> <password> "<command>" ["<command>"...]
const net = require('net');
const [, , PORT, PASS, ...COMMANDS] = process.argv;

function frame(id, type, body) {
  const payload = Buffer.concat([Buffer.from(body, 'utf8'), Buffer.from([0, 0])]);
  const buf = Buffer.alloc(12 + payload.length);
  buf.writeInt32LE(8 + payload.length, 0);
  buf.writeInt32LE(id, 4);
  buf.writeInt32LE(type, 8);
  payload.copy(buf, 12);
  return buf;
}

const sock = net.connect(parseInt(PORT, 10), '127.0.0.1');
let buffer = Buffer.alloc(0);
let queue = [...COMMANDS];
let authed = false;

sock.on('connect', () => sock.write(frame(1, 3, PASS)));
sock.on('data', (d) => {
  buffer = Buffer.concat([buffer, d]);
  while (buffer.length >= 4) {
    const len = buffer.readInt32LE(0);
    if (buffer.length < 4 + len) break;
    const id = buffer.readInt32LE(4);
    const body = buffer.slice(12, 4 + len - 2).toString('utf8');
    buffer = buffer.slice(4 + len);
    if (!authed) {
      if (id === -1) { console.error('auth failed'); process.exit(1); }
      authed = true;
    } else if (body.length) {
      console.log(body);
    }
    if (queue.length) sock.write(frame(2, 2, queue.shift()));
    else { sock.end(); process.exit(0); }
  }
});
sock.on('error', (e) => { console.error(e.message); process.exit(1); });
setTimeout(() => process.exit(0), 10000);
