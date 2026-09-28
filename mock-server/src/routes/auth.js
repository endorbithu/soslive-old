import express from 'express';
import bcrypt from 'bcryptjs';
import { z } from 'zod';
import { issueSession, LoginThrottle, requireAuth } from '../auth.js';
import { verifyFacebook, verifyGoogle } from '../sso.js';
import { ApiError, conflict, parseBody, unauthorized, wrap } from '../http.js';

const registerSchema = z.object({
  email: z.string().trim().toLowerCase().email(),
  password: z.string().min(8, 'must be at least 8 characters').max(200),
  displayName: z.string().trim().min(1).max(80),
});

const loginSchema = z.object({
  email: z.string().trim().toLowerCase().min(1),
  password: z.string().min(1),
});

export function authRouter({ config, db, fetchImpl }) {
  const router = express.Router();
  const throttle = new LoginThrottle(config.maxFailedLogins, config.failedLoginWindowSec);

  router.post('/register', wrap(async (req, res) => {
    const body = parseBody(registerSchema, req.body);
    if (db.findUserByEmail(body.email)) throw conflict('An account with this e-mail already exists', 'email_taken');
    const user = db.insertUser({
      email: body.email,
      displayName: body.displayName,
      passwordHash: await bcrypt.hash(body.password, 10),
    });
    res.status(201).json(issueSession(config, db, user));
  }));

  router.post('/login', wrap(async (req, res) => {
    const body = parseBody(loginSchema, req.body);
    if (throttle.isBlocked(body.email)) {
      throw new ApiError(429, 'too_many_attempts', 'Too many failed login attempts, try again in 15 minutes');
    }
    const user = db.findUserByEmail(body.email);
    const ok = user?.passwordHash && (await bcrypt.compare(body.password, user.passwordHash));
    if (!ok) {
      throttle.fail(body.email);
      throw unauthorized('Wrong e-mail or password', 'invalid_credentials');
    }
    throttle.reset(body.email);
    res.json(issueSession(config, db, user));
  }));

  const ssoLogin = (provider, verify, tokenField) => wrap(async (req, res) => {
    const identity = await verify(config, req.body?.[tokenField], fetchImpl);
    let user = db.findUserByProvider(provider, identity.subject);
    let created = false;
    if (!user) {
      user = db.findUserByEmail(identity.email);
      if (user) {
        // Link the provider to the existing account with the same e-mail.
        user.providers = { ...user.providers, [provider]: identity.subject };
        if (!user.avatarUrl && identity.avatarUrl) user.avatarUrl = identity.avatarUrl;
        db.save();
      } else {
        user = db.insertUser({
          email: identity.email,
          displayName: identity.displayName,
          avatarUrl: identity.avatarUrl,
          providers: { [provider]: identity.subject },
        });
        created = true;
      }
    }
    res.status(created ? 201 : 200).json(issueSession(config, db, user));
  });

  router.post('/google', ssoLogin('google', verifyGoogle, 'idToken'));
  router.post('/facebook', ssoLogin('facebook', verifyFacebook, 'accessToken'));

  router.post('/refresh', wrap(async (req, res) => {
    const token = req.body?.refreshToken;
    const row = token ? db.takeRefreshToken(token) : null;
    const user = row ? db.findUserById(row.userId) : null;
    if (!user) throw unauthorized('Refresh token is invalid or expired', 'invalid_refresh_token');
    res.json(issueSession(config, db, user));
  }));

  router.post('/logout', requireAuth(config, db), (req, res) => {
    if (req.body?.refreshToken) db.revokeRefreshToken(req.body.refreshToken);
    res.status(204).end();
  });

  return router;
}
