import { test, describe, before } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import request from 'supertest';
import { createApp, DEMO_USER } from '../src/app.js';
import { loadConfig } from '../src/config.js';
import { Db } from '../src/db.js';

process.env.NODE_ENV = 'test';

function makeApp(overrides = {}, fetchImpl) {
  const config = {
    ...loadConfig({}),
    rtmpUrl: 'rtmp://rtmp.test:1935/live',
    jwtSecret: 'test-secret',
    dbFile: ':memory:',
    uploadDir: fs.mkdtempSync(path.join(os.tmpdir(), 'soslive-uploads-')),
    publicWebUrl: 'http://soslive.test',
    ...overrides,
  };
  return createApp(config, { db: new Db(':memory:'), fetchImpl });
}

async function register(app, email = 'anna@example.com') {
  const res = await request(app).post('/auth/register').send({ email, password: 'secret123', displayName: 'Anna' });
  assert.equal(res.status, 201, JSON.stringify(res.body));
  return res.body;
}

const bearer = (session) => ({ Authorization: `Bearer ${session.accessToken}` });

describe('health', () => {
  test('reports simulated SSO when no provider credentials are configured', async () => {
    const res = await request(makeApp()).get('/health');
    assert.equal(res.status, 200);
    assert.deepEqual(res.body.sso, { google: 'simulated', facebook: 'simulated' });
  });
});

describe('password auth', () => {
  test('demo user can log in', async () => {
    const res = await request(makeApp()).post('/auth/login').send({ email: DEMO_USER.email, password: DEMO_USER.password });
    assert.equal(res.status, 200);
    assert.equal(res.body.user.email, DEMO_USER.email);
    assert.ok(res.body.accessToken && res.body.refreshToken);
  });

  test('register, duplicate e-mail, wrong password, /me', async () => {
    const app = makeApp();
    const session = await register(app);
    assert.deepEqual(session.user.providers, ['password']);

    const dup = await request(app).post('/auth/register').send({ email: 'ANNA@example.com', password: 'secret123', displayName: 'X' });
    assert.equal(dup.status, 409);
    assert.equal(dup.body.error.code, 'email_taken');

    const bad = await request(app).post('/auth/login').send({ email: 'anna@example.com', password: 'nope' });
    assert.equal(bad.status, 401);
    assert.equal(bad.body.error.code, 'invalid_credentials');

    const me = await request(app).get('/me').set(bearer(session));
    assert.equal(me.status, 200);
    assert.equal(me.body.displayName, 'Anna');
  });

  test('validation errors are 400', async () => {
    const res = await request(makeApp()).post('/auth/register').send({ email: 'x', password: '1', displayName: '' });
    assert.equal(res.status, 400);
    assert.equal(res.body.error.code, 'validation_error');
  });

  test('login is throttled after repeated failures', async () => {
    const app = makeApp({ maxFailedLogins: 2 });
    await register(app);
    for (let i = 0; i < 2; i++) {
      await request(app).post('/auth/login').send({ email: 'anna@example.com', password: 'wrong' });
    }
    const res = await request(app).post('/auth/login').send({ email: 'anna@example.com', password: 'secret123' });
    assert.equal(res.status, 429);
  });

  test('refresh token rotates and old one is rejected', async () => {
    const app = makeApp();
    const session = await register(app);
    const r1 = await request(app).post('/auth/refresh').send({ refreshToken: session.refreshToken });
    assert.equal(r1.status, 200);
    assert.notEqual(r1.body.refreshToken, session.refreshToken);
    const r2 = await request(app).post('/auth/refresh').send({ refreshToken: session.refreshToken });
    assert.equal(r2.status, 401);
  });

  test('protected routes need a valid token', async () => {
    const app = makeApp();
    assert.equal((await request(app).get('/me')).status, 401);
    const res = await request(app).get('/me').set('Authorization', 'Bearer garbage');
    assert.equal(res.status, 401);
    assert.equal(res.body.error.code, 'invalid_token');
  });
});

describe('SSO (simulated)', () => {
  test('mock Google token creates a user, then links by e-mail to Facebook', async () => {
    const app = makeApp();
    const g = await request(app).post('/auth/google').send({ idToken: 'mock:bela@example.com|Béla' });
    assert.equal(g.status, 201);
    assert.equal(g.body.user.displayName, 'Béla');
    assert.deepEqual(g.body.user.providers, ['google']);

    const again = await request(app).post('/auth/google').send({ idToken: 'mock:bela@example.com' });
    assert.equal(again.status, 200);
    assert.equal(again.body.user.id, g.body.user.id);

    const fb = await request(app).post('/auth/facebook').send({ accessToken: 'mock:bela@example.com' });
    assert.equal(fb.status, 200);
    assert.equal(fb.body.user.id, g.body.user.id);
    assert.deepEqual(fb.body.user.providers.sort(), ['facebook', 'google']);
  });

  test('unverified real Google ID token is decoded in simulated mode', async () => {
    const payload = Buffer.from(JSON.stringify({ sub: '1234', email: 'c@example.com', name: 'Cecil', exp: Date.now() / 1000 + 60 })).toString('base64url');
    const res = await request(makeApp()).post('/auth/google').send({ idToken: `x.${payload}.y` });
    assert.equal(res.status, 201);
    assert.equal(res.body.user.email, 'c@example.com');
  });

  test('bad mock token is rejected', async () => {
    const res = await request(makeApp()).post('/auth/facebook').send({ accessToken: 'mock:not-an-email' });
    assert.equal(res.status, 400);
  });

  test('verified Google mode checks the audience', async () => {
    const fakeFetch = async () => ({ ok: true, json: async () => ({ aud: 'other', iss: 'accounts.google.com', sub: '1', email: 'a@b.cd' }) });
    const res = await request(makeApp({ googleClientId: 'mine' }, fakeFetch)).post('/auth/google').send({ idToken: 'a.b.c' });
    assert.equal(res.status, 401);
  });
});

describe('profile', () => {
  test('SOS contacts are validated and stored', async () => {
    const app = makeApp();
    const session = await register(app);
    const bad = await request(app).patch('/me').set(bearer(session)).send({ sosContacts: ['06301234567'] });
    assert.equal(bad.status, 400);
    const ok = await request(app).patch('/me').set(bearer(session)).send({ sosContacts: ['+36301234567'], sosMessage: 'Help!' });
    assert.equal(ok.status, 200);
    assert.deepEqual(ok.body.sosContacts, ['+36301234567']);
    assert.equal(ok.body.sosMessage, 'Help!');
  });
});

describe('events', () => {
  let app;
  let session;
  before(async () => {
    app = makeApp();
    session = await register(app);
  });

  test('SOS event returns an RTMP stream target and share URL', async () => {
    const res = await request(app).post('/events').set(bearer(session)).send({ type: 'SOS', lat: 47.5, lng: 19.04 });
    assert.equal(res.status, 201);
    assert.equal(res.body.status, 'LIVE');
    assert.equal(res.body.stream.url, 'rtmp://rtmp.test:1935/live');
    assert.match(res.body.stream.streamKey, /^\d{8}_\d+_\d+_[0-9a-f]{16}$/);
    assert.equal(res.body.stream.publishUrl, `rtmp://rtmp.test:1935/live/${res.body.stream.streamKey}`);
    assert.equal(res.body.shareUrl, `http://soslive.test/e/${res.body.id}`);
    assert.deepEqual([res.body.lastLocation.lat, res.body.lastLocation.lng], [47.5, 19.04]);
  });

  test('fixed stream key is used when configured', async () => {
    const a = makeApp({ rtmpStreamKey: 'yt-key' });
    const s = await register(a);
    const res = await request(a).post('/events').set(bearer(s)).send({ type: 'LIVE' });
    assert.equal(res.body.stream.publishUrl, 'rtmp://rtmp.test:1935/live/yt-key');
  });

  test('photo event has no stream; location, stop, list', async () => {
    const photo = await request(app).post('/events').set(bearer(session)).send({ type: 'PHOTO' });
    assert.equal(photo.body.stream, null);

    const loc = await request(app).post(`/events/${photo.body.id}/location`).set(bearer(session)).send({ lat: 1, lng: 2 });
    assert.equal(loc.status, 200);
    assert.equal(loc.body.lastLocation.lat, 1);

    const stop = await request(app).post(`/events/${photo.body.id}/stop`).set(bearer(session));
    assert.equal(stop.body.status, 'STOPPED');

    const list = await request(app).get('/events').set(bearer(session));
    assert.ok(list.body.items.length >= 2);
    assert.ok(list.body.items[0].id > list.body.items[1].id);
  });

  test('other users cannot access an event', async () => {
    const ev = await request(app).post('/events').set(bearer(session)).send({ type: 'LIVE' });
    const other = await register(app, 'other@example.com');
    const res = await request(app).get(`/events/${ev.body.id}`).set(bearer(other));
    assert.equal(res.status, 403);
  });

  test('comments from owner and public viewers, with sinceId', async () => {
    const ev = await request(app).post('/events').set(bearer(session)).send({ type: 'SOS' });
    const id = ev.body.id;
    const c1 = await request(app).post(`/events/${id}/comments`).set(bearer(session)).send({ message: 'I am here' });
    assert.equal(c1.status, 201);
    const viewer = await request(app).post(`/e/${id}/comments`).type('form').send({ name: 'Viewer', message: 'On my way' });
    assert.equal(viewer.status, 303);

    const all = await request(app).get(`/events/${id}/comments`).set(bearer(session));
    assert.equal(all.body.total, 2);
    assert.deepEqual(all.body.items.map((c) => c.authorType), ['OWNER', 'VIEWER']);

    const newer = await request(app).get(`/events/${id}/comments?sinceId=${c1.body.id}`).set(bearer(session));
    assert.equal(newer.body.items.length, 1);
    assert.equal(newer.body.items[0].message, 'On my way');

    const page = await request(app).get(`/e/${id}`);
    assert.equal(page.status, 200);
    assert.match(page.text, /On my way/);
  });

  test('photo upload', async () => {
    const ev = await request(app).post('/events').set(bearer(session)).send({ type: 'PHOTO' });
    const jpeg = Buffer.from([0xff, 0xd8, 0xff, 0xe0, 0, 0x10, 0x4a, 0x46, 0x49, 0x46, 0, 1, 0xff, 0xd9]);
    const res = await request(app)
      .post(`/events/${ev.body.id}/photos`)
      .set(bearer(session))
      .attach('photo', jpeg, { filename: 'p.jpg', contentType: 'image/jpeg' });
    assert.equal(res.status, 201, JSON.stringify(res.body));
    assert.match(res.body.url, /^http:\/\/soslive\.test\/uploads\/.+\.jpg$/);

    const file = await request(app).get(new URL(res.body.url).pathname);
    assert.equal(file.status, 200);

    const missing = await request(app).post(`/events/${ev.body.id}/photos`).set(bearer(session));
    assert.equal(missing.status, 400);
  });
});
