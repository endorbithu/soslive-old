import 'dotenv/config';
import crypto from 'node:crypto';

/**
 * Reads the server configuration from environment variables (.env is loaded automatically).
 * The only value that has to be provided is RTMP_URL - everything else has a dev default.
 */
export function loadConfig(env = process.env) {
  const port = Number(env.PORT || 3000);
  return {
    port,
    // RTMP ingest, e.g. rtmp://my-rtmp-host:1935/live  (the stream key is appended to it)
    rtmpUrl: (env.RTMP_URL || '').trim().replace(/\/+$/, ''),
    // Optional fixed stream key (e.g. a YouTube / Facebook Live key). If empty, a key is generated per event.
    rtmpStreamKey: (env.RTMP_STREAM_KEY || '').trim(),
    rtmpKeyPrefix: (env.RTMP_KEY_PREFIX || '').trim(),
    publicWebUrl: (env.PUBLIC_WEB_URL || `http://localhost:${port}`).replace(/\/+$/, ''),
    jwtSecret: env.JWT_SECRET || crypto.randomBytes(32).toString('hex'),
    accessTokenTtlSec: Number(env.ACCESS_TOKEN_TTL_SEC || 15 * 60),
    refreshTokenTtlSec: Number(env.REFRESH_TOKEN_TTL_SEC || 30 * 24 * 3600),
    dbFile: env.DB_FILE || 'data/db.json',
    uploadDir: env.UPLOAD_DIR || 'data/uploads',
    googleClientId: (env.GOOGLE_CLIENT_ID || '').trim(),
    facebookAppId: (env.FACEBOOK_APP_ID || '').trim(),
    facebookAppSecret: (env.FACEBOOK_APP_SECRET || '').trim(),
    seedDemoUser: env.SEED_DEMO_USER !== 'false',
    maxFailedLogins: Number(env.MAX_FAILED_LOGINS || 5),
    failedLoginWindowSec: Number(env.FAILED_LOGIN_WINDOW_SEC || 15 * 60),
  };
}

export function validateConfig(config) {
  const errors = [];
  if (!config.rtmpUrl) {
    errors.push('RTMP_URL is required (e.g. RTMP_URL=rtmp://your-server:1935/live). Copy .env.example to .env and fill it in.');
  } else if (!/^rtmps?:\/\//i.test(config.rtmpUrl)) {
    errors.push(`RTMP_URL must start with rtmp:// or rtmps:// (got "${config.rtmpUrl}")`);
  }
  return errors;
}
