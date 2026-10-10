import { after, before, test } from 'node:test';
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { createServer } from 'node:net';
import { WebSocket } from 'ws';

let child, base, port;

async function freePort() {
  return await new Promise((resolve, reject) => {
    const s = createServer();
    s.once('error', reject);
    s.listen(0, '127.0.0.1', () => { const p = s.address().port; s.close(() => resolve(p)); });
  });
}

async function waitForHealth() {
  for (let i = 0; i < 100; i++) {
    try { if ((await fetch(`${base}/health`)).ok) return; } catch {}
    await new Promise(r => setTimeout(r, 50));
  }
  throw new Error('signaling service did not start');
}

function ws(url) {
  const socket = new WebSocket(url);
  const messages = [];
  const waiters = [];
  socket.on('message', b => {
    const m = JSON.parse(b.toString());
    const waiter = waiters.shift();
    if (waiter) waiter(m); else messages.push(m);
  });
  return {
    socket,
    next: () => messages.length ? Promise.resolve(messages.shift()) : new Promise(resolve => waiters.push(resolve)),
    open: () => new Promise((resolve, reject) => { socket.once('open', resolve); socket.once('error', reject); }),
  };
}

before(async () => {
  port = await freePort();
  base = `http://127.0.0.1:${port}`;
  child = spawn(process.execPath, ['server.mjs'], {
    cwd: new URL('.', import.meta.url),
    env: { ...process.env, PORT: String(port), TURN_SECRET: 'test-turn-secret-0123456789abcdef', TURN_URLS: 'turn:localhost:3478' },
    stdio: 'ignore',
  });
  await waitForHealth();
});

after(() => { child?.kill('SIGTERM'); });

test('session requires distinct host/helper secrets and produces short-lived TURN credentials', async () => {
  const created = await (await fetch(`${base}/v1/sessions`, { method: 'POST' })).json();
  assert.equal(created.sessionId.length, 32);
  assert.notEqual(created.hostToken, created.joinToken);
  const turn = await fetch(`${base}/v1/turn?session=${created.sessionId}&role=host&token=${encodeURIComponent(created.hostToken)}`);
  assert.equal(turn.status, 200);
  const credentials = await turn.json();
  assert.deepEqual(credentials.urls, ['turn:localhost:3478']);
  assert.ok(credentials.expiresAt > Date.now());
  const denied = await fetch(`${base}/v1/turn?session=${created.sessionId}&role=host&token=${encodeURIComponent(created.joinToken)}`);
  assert.equal(denied.status, 403);
});

test('SDP and ICE signals are forwarded only between the paired roles; helper can resume', async () => {
  const session = await (await fetch(`${base}/v1/sessions`, { method: 'POST' })).json();
  const host = ws(`ws://127.0.0.1:${port}/v1/signal?session=${session.sessionId}&role=host&token=${encodeURIComponent(session.hostToken)}`);
  const helper = ws(`ws://127.0.0.1:${port}/v1/signal?session=${session.sessionId}&role=helper&token=${encodeURIComponent(session.joinToken)}`);
  await Promise.all([host.open(), helper.open()]);
  const joined = await helper.next();
  assert.equal(joined.type, 'joined');
  assert.ok(joined.payload.resumeToken);
  assert.deepEqual(await host.next(), { type: 'peer-joined', payload: {} });
  const resumedTurn = await fetch(`${base}/v1/turn?session=${session.sessionId}&role=helper&token=${encodeURIComponent(joined.payload.resumeToken)}`);
  assert.equal(resumedTurn.status, 200);
  host.socket.send(JSON.stringify({ type: 'offer', payload: { sdp: 'test-offer' } }));
  assert.deepEqual(await helper.next(), { type: 'offer', payload: { sdp: 'test-offer' } });
  helper.socket.send(JSON.stringify({ type: 'ice', payload: { candidate: 'test-candidate' } }));
  assert.deepEqual(await host.next(), { type: 'ice', payload: { candidate: 'test-candidate' } });
  host.socket.close(); helper.socket.close();
  await Promise.all([
    new Promise(r => host.socket.once('close', r)),
    new Promise(r => helper.socket.once('close', r)),
  ]);
  const resumed = ws(`ws://127.0.0.1:${port}/v1/signal?session=${session.sessionId}&role=helper&token=${encodeURIComponent(joined.payload.resumeToken)}`);
  await resumed.open();
  resumed.socket.close();
});
