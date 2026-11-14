const net = require('net');

const HOST = process.env.RCON_HOST || '127.0.0.1';
const PORT = Number(process.env.RCON_PORT || 25575);
const PASS = process.env.RCON_PASS || 'livingcity-dev';

function packet(id, type, body) {
  const b = Buffer.from(body, 'utf8');
  const buf = Buffer.alloc(14 + b.length);
  buf.writeInt32LE(10 + b.length, 0);
  buf.writeInt32LE(id, 4);
  buf.writeInt32LE(type, 8);
  b.copy(buf, 12);
  return buf;
}

class Rcon {
  constructor() {
    this.sock = null;
    this.buf = Buffer.alloc(0);
    this.pending = new Map();
    this.nextId = 2;
  }

  connect(timeoutMs = 10000) {
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error('rcon connect timeout')), timeoutMs);
      this.sock = net.connect(PORT, HOST, () => this.sock.write(packet(1, 3, PASS)));
      this.sock.on('error', (e) => { clearTimeout(timer); reject(e); });
      this.onPacket = (id, body) => {
        clearTimeout(timer);
        if (id === -1) reject(new Error('rcon auth failed'));
        else resolve(this);
      };
      this.sock.on('data', (chunk) => this.onData(chunk));
    });
  }

  onData(chunk) {
    this.buf = Buffer.concat([this.buf, chunk]);
    while (this.buf.length >= 12) {
      const len = this.buf.readInt32LE(0);
      if (this.buf.length < len + 4) break;
      const id = this.buf.readInt32LE(4);
      const body = this.buf.subarray(12, 4 + len - 2).toString('utf8');
      this.buf = this.buf.subarray(len + 4);
      if (id === 1 && this.onPacket) {
        const cb = this.onPacket;
        this.onPacket = null;
        cb(id, body);
      } else {
        const p = this.pending.get(id);
        if (p) { this.pending.delete(id); p.resolve(body); }
      }
    }
  }

  cmd(command, timeoutMs = 15000) {
    const id = this.nextId++;
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pending.delete(id);
        reject(new Error('rcon timeout: ' + command));
      }, timeoutMs);
      this.pending.set(id, { resolve: (v) => { clearTimeout(timer); resolve(v); } });
      this.sock.write(packet(id, 2, command));
    });
  }

  close() { try { this.sock && this.sock.end(); } catch (e) {} }
}

async function main() {
  const rcon = new Rcon();
  await rcon.connect();
  for (const c of process.argv.slice(2)) {
    const out = await rcon.cmd(c);
    console.log('> ' + c + '\n' + out.trim());
  }
  rcon.close();
}

if (require.main === module) {
  main().catch((e) => { console.error('ERR', e.message); process.exit(1); });
}

module.exports = { Rcon };
