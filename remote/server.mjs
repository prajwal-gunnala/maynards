import { createHmac, randomBytes, timingSafeEqual } from 'node:crypto';
import http from 'node:http';
import { WebSocketServer, WebSocket } from 'ws';

const PORT = Number(process.env.PORT || 3000);
const TURN_SECRET = process.env.TURN_SECRET || '';
const TURN_URLS = (process.env.TURN_URLS || '').split(',').map(s => s.trim()).filter(Boolean);
if (TURN_SECRET && Buffer.byteLength(TURN_SECRET) < 32) throw new Error('TURN_SECRET must contain at least 32 bytes');
const sessions = new Map();
const rates = new Map();
const TTL_MS = 10 * 60_000;
const MAX_SIGNAL_BYTES = 64 * 1024;
const SIGNAL_TYPES = new Set(['offer', 'answer', 'ice', 'hangup']);

const json = (res, status, body) => {
  const data = Buffer.from(JSON.stringify(body));
  res.writeHead(status, { 'content-type': 'application/json', 'content-length': data.length,
    'cache-control': 'no-store', 'x-content-type-options': 'nosniff' });
  res.end(data);
};

function rateLimit(req, res) {
  const ip = req.socket.remoteAddress || 'unknown';
  const now = Date.now();
  const row = rates.get(ip) || { start: now, count: 0 };
  if (now - row.start > 10 * 60_000) { row.start = now; row.count = 0; }
  row.count++;
  rates.set(ip, row);
  if (row.count > 20) { json(res, 429, { error: 'too many requests' }); return false; }
  return true;
}

function equalToken(a, b) {
  if (typeof a !== 'string' || typeof b !== 'string') return false;
  const aa = Buffer.from(a), bb = Buffer.from(b);
  return aa.length === bb.length && timingSafeEqual(aa, bb);
}

function getSession(id) {
  const s = sessions.get(id);
  if (!s || s.expiresAt <= Date.now()) { sessions.delete(id); return null; }
  return s;
}

const server = http.createServer((req, res) => {
  const url = new URL(req.url || '/', 'http://localhost');
  if (req.method === 'GET' && url.pathname === '/health') return json(res, 200, { ok: true });
  if (req.method === 'POST' && url.pathname === '/v1/sessions') {
    if (!rateLimit(req, res)) return;
    if (sessions.size >= 10_000) return json(res, 503, { error: 'session capacity reached' });
    const sessionId = randomBytes(16).toString('hex');
    const hostToken = randomBytes(32).toString('base64url');
    const joinToken = randomBytes(32).toString('base64url');
    const expiresAt = Date.now() + TTL_MS;
    sessions.set(sessionId, { hostToken, joinToken, expiresAt, peers: new Map() });
    return json(res, 201, { sessionId, hostToken, joinToken, expiresAt, turn: TURN_URLS.length && TURN_SECRET ? TURN_URLS : [] });
  }
  if (req.method === 'GET' && url.pathname === '/v1/turn') {
    const s = getSession(url.searchParams.get('session'));
    const role = url.searchParams.get('role');
    const token = url.searchParams.get('token');
    const authorized = role === 'host'
      ? s && equalToken(token, s.hostToken)
      : s && (equalToken(token, s.joinToken) || (s.helperUsed && equalToken(token, s.helperToken)));
    if (!s || !['host', 'helper'].includes(role) || !authorized)
      return json(res, 403, { error: 'not authorized' });
    if (!TURN_URLS.length || !TURN_SECRET) return json(res, 200, {
      urls: [], username: '', credential: '', expiresAt: Date.now() + 60_000,
    }); // local same-LAN testing may use host candidates without TURN
    const expires = Math.floor(Date.now() / 1000) + 60 * 60;
    const username = `${expires}:${url.searchParams.get('session')}`;
    const credential = createHmac('sha1', TURN_SECRET).update(username).digest('base64');
    return json(res, 200, { urls: TURN_URLS, username, credential, expiresAt: expires * 1000 });
  }
  json(res, 404, { error: 'not found' });
});

const wss = new WebSocketServer({ noServer: true, maxPayload: MAX_SIGNAL_BYTES, perMessageDeflate: false });
server.on('upgrade', (req, socket, head) => {
  const url = new URL(req.url || '/', 'http://localhost');
  if (url.pathname !== '/v1/signal') { socket.destroy(); return; }
  const s = getSession(url.searchParams.get('session'));
  const role = url.searchParams.get('role');
  const token = url.searchParams.get('token');
  const helperInitial = role === 'helper' && s && !s.helperUsed && equalToken(token, s.joinToken);
  const helperResume = role === 'helper' && s && s.helperUsed && equalToken(token, s.helperToken);
  const authorized = role === 'host' ? s && equalToken(token, s.hostToken) : (helperInitial || helperResume);
  if (!s || !['host', 'helper'].includes(role) || !authorized) {
    socket.write('HTTP/1.1 403 Forbidden\r\nConnection: close\r\n\r\n'); socket.destroy(); return;
  }
  wss.handleUpgrade(req, socket, head, ws => {
    if (helperInitial) {
      s.helperUsed = true;
      s.helperToken = randomBytes(32).toString('base64url');
    }
    const old = s.peers.get(role);
    if (old?.readyState === WebSocket.OPEN) old.close(4001, 'replaced by a new connection');
    s.peers.set(role, ws);
    if (helperInitial) ws.send(JSON.stringify({ type: 'joined', payload: { resumeToken: s.helperToken } }));
    if (role === 'helper') {
      const host = s.peers.get('host');
      if (host?.readyState === WebSocket.OPEN) host.send(JSON.stringify({ type: 'peer-joined', payload: {} }));
    }
    ws.isAlive = true;
    ws.on('pong', () => { ws.isAlive = true; });
    ws.on('message', data => {
      if (data.length > MAX_SIGNAL_BYTES) { ws.close(1009, 'signal too large'); return; }
      let msg;
      try { msg = JSON.parse(data.toString()); } catch { ws.close(1003, 'invalid JSON'); return; }
      if (!msg || !SIGNAL_TYPES.has(msg.type) || (msg.type !== 'hangup' && typeof msg.payload !== 'object')) {
        ws.close(1008, 'invalid signal'); return;
      }
      const other = s.peers.get(role === 'host' ? 'helper' : 'host');
      if (other?.readyState === WebSocket.OPEN) other.send(JSON.stringify({ type: msg.type, payload: msg.payload ?? null }));
    });
    ws.on('close', () => { if (s.peers.get(role) === ws) s.peers.delete(role); });
    ws.on('error', () => {});
  });
});

const cleanup = setInterval(() => {
  const now = Date.now();
  for (const [id, s] of sessions) if (s.expiresAt <= now) {
    for (const ws of s.peers.values()) ws.close(4000, 'session expired');
    sessions.delete(id);
  }
  for (const [ip, row] of rates) if (now - row.start > 20 * 60_000) rates.delete(ip);
}, 60_000);
cleanup.unref();
const heartbeat = setInterval(() => {
  for (const ws of wss.clients) {
    if (ws.isAlive === false) { ws.terminate(); continue; }
    ws.isAlive = false;
    ws.ping();
  }
}, 30_000);
heartbeat.unref();
server.listen(PORT, '0.0.0.0', () => console.log(`MeshAI signaling listening on ${PORT}`));
