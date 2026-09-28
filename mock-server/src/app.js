import express from 'express';
import bcrypt from 'bcryptjs';
import { requireAuth } from './auth.js';
import { Db } from './db.js';
import { ApiError } from './http.js';
import { authRouter } from './routes/auth.js';
import { meRouter } from './routes/me.js';
import { eventsRouter, publicRouter } from './routes/events.js';

export const DEMO_USER = { email: 'demo@soslive.local', password: 'demo1234', displayName: 'Demo User' };

export function seedDemoUser(db) {
  if (db.users.length > 0) return;
  db.insertUser({
    email: DEMO_USER.email,
    displayName: DEMO_USER.displayName,
    passwordHash: bcrypt.hashSync(DEMO_USER.password, 10),
  });
}

/**
 * Builds the Express app. `fetchImpl` is injectable so SSO verification can be tested offline.
 */
export function createApp(config, { db = new Db(config.dbFile), fetchImpl = fetch } = {}) {
  if (config.seedDemoUser) seedDemoUser(db);

  const app = express();
  app.disable('x-powered-by');
  app.use(express.json({ limit: '1mb' }));
  app.use((req, _res, next) => {
    if (process.env.NODE_ENV !== 'test') console.log(`${new Date().toISOString()} ${req.method} ${req.originalUrl}`);
    next();
  });

  const auth = requireAuth(config, db);

  app.get('/health', (_req, res) => res.json({
    status: 'ok',
    rtmpConfigured: Boolean(config.rtmpUrl),
    sso: {
      google: config.googleClientId ? 'verified' : 'simulated',
      facebook: config.facebookAppId && config.facebookAppSecret ? 'verified' : 'simulated',
    },
  }));

  app.use('/auth', authRouter({ config, db, fetchImpl }));
  app.use('/me', auth, meRouter({ config, db }));
  app.use('/events', auth, eventsRouter({ config, db }));
  app.use('/e', publicRouter({ config, db }));
  app.use('/uploads', express.static(config.uploadDir, { fallthrough: false }));

  app.use((_req, _res, next) => next(new ApiError(404, 'not_found', 'Route not found')));
  // eslint-disable-next-line no-unused-vars
  app.use((err, _req, res, _next) => {
    if (err.type === 'entity.parse.failed') err = new ApiError(400, 'invalid_json', 'Request body is not valid JSON');
    if (err.status === 404 && !(err instanceof ApiError)) err = new ApiError(404, 'not_found', 'Not found');
    if (!(err instanceof ApiError)) {
      console.error(err);
      err = new ApiError(500, 'internal_error', 'Internal server error');
    }
    res.status(err.status).json({ error: { code: err.code, message: err.message } });
  });

  return app;
}
