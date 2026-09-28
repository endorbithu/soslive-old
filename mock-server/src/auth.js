import crypto from 'node:crypto';
import jwt from 'jsonwebtoken';
import { unauthorized } from './http.js';

export function toUserDto(user) {
  const providers = Object.keys(user.providers ?? {});
  if (user.passwordHash) providers.unshift('password');
  return {
    id: user.id,
    email: user.email,
    displayName: user.displayName,
    avatarUrl: user.avatarUrl ?? null,
    providers,
    sosContacts: user.sosContacts ?? [],
    sosMessage: user.sosMessage ?? '',
    createdAt: user.createdAt,
  };
}

/** Issues a short lived JWT access token + an opaque, rotating refresh token. */
export function issueSession(config, db, user) {
  const accessToken = jwt.sign({ sub: String(user.id), email: user.email }, config.jwtSecret, {
    expiresIn: config.accessTokenTtlSec,
  });
  const refreshToken = crypto.randomBytes(32).toString('base64url');
  db.addRefreshToken(refreshToken, user.id, Date.now() + config.refreshTokenTtlSec * 1000);
  return {
    accessToken,
    refreshToken,
    tokenType: 'Bearer',
    expiresIn: config.accessTokenTtlSec,
    user: toUserDto(user),
  };
}

/** Express middleware: requires "Authorization: Bearer <jwt>" and sets req.user. */
export function requireAuth(config, db) {
  return (req, _res, next) => {
    const header = req.get('authorization') || '';
    const match = header.match(/^Bearer\s+(.+)$/i);
    if (!match) return next(unauthorized());
    try {
      const payload = jwt.verify(match[1], config.jwtSecret);
      const user = db.findUserById(Number(payload.sub));
      if (!user) return next(unauthorized('User no longer exists', 'invalid_token'));
      req.user = user;
      return next();
    } catch (e) {
      const code = e.name === 'TokenExpiredError' ? 'token_expired' : 'invalid_token';
      return next(unauthorized(e.message, code));
    }
  };
}

/** Simple in-memory brute force protection for password logins (the old API answered "-3"). */
export class LoginThrottle {
  constructor(maxFailures, windowSec) {
    this.maxFailures = maxFailures;
    this.windowMs = windowSec * 1000;
    this.failures = new Map();
  }

  isBlocked(key) {
    const entry = this.failures.get(key);
    if (!entry) return false;
    if (Date.now() - entry.first > this.windowMs) {
      this.failures.delete(key);
      return false;
    }
    return entry.count >= this.maxFailures;
  }

  fail(key) {
    const entry = this.failures.get(key);
    if (!entry || Date.now() - entry.first > this.windowMs) {
      this.failures.set(key, { count: 1, first: Date.now() });
    } else {
      entry.count += 1;
    }
  }

  reset(key) {
    this.failures.delete(key);
  }
}
