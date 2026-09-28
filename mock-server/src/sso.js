import { unauthorized, badRequest } from './http.js';

/**
 * SSO token verification.
 *
 * Simulated mode (default, no provider credentials configured):
 *   - token "mock:<email>" or "mock:<email>|<Display Name>" is accepted for both providers
 *     (the Android app sends this when it has no Google/Facebook client id configured),
 *   - a real Google ID token is decoded WITHOUT signature verification,
 *   - a real Facebook access token is resolved via Graph /me (not bound to an app).
 *
 * Verified mode: set GOOGLE_CLIENT_ID and/or FACEBOOK_APP_ID + FACEBOOK_APP_SECRET.
 *
 * Every function resolves to { subject, email, displayName, avatarUrl }.
 */

function parseMockToken(provider, token) {
  const raw = token.slice('mock:'.length).trim();
  const [emailPart, namePart] = raw.split('|');
  const email = (emailPart || '').trim().toLowerCase();
  if (!/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(email)) {
    throw badRequest('Mock SSO token must look like "mock:user@example.com|Optional Name"', 'invalid_sso_token');
  }
  return {
    subject: `mock-${provider}-${email}`,
    email,
    displayName: (namePart || '').trim() || email.split('@')[0],
    avatarUrl: null,
  };
}

function decodeJwtPayload(token) {
  const parts = token.split('.');
  if (parts.length !== 3) throw unauthorized('Malformed Google ID token', 'invalid_sso_token');
  try {
    return JSON.parse(Buffer.from(parts[1], 'base64url').toString('utf8'));
  } catch {
    throw unauthorized('Malformed Google ID token', 'invalid_sso_token');
  }
}

export async function verifyGoogle(config, idToken, fetchImpl = fetch) {
  if (!idToken) throw badRequest('idToken is required', 'validation_error');
  if (idToken.startsWith('mock:')) return parseMockToken('google', idToken);

  let claims;
  if (config.googleClientId) {
    const res = await fetchImpl(`https://oauth2.googleapis.com/tokeninfo?id_token=${encodeURIComponent(idToken)}`);
    if (!res.ok) throw unauthorized('Google rejected the ID token', 'invalid_sso_token');
    claims = await res.json();
    if (claims.aud !== config.googleClientId) {
      throw unauthorized('Google ID token was issued for another client', 'invalid_sso_token');
    }
    if (!['accounts.google.com', 'https://accounts.google.com'].includes(claims.iss)) {
      throw unauthorized('Unexpected Google token issuer', 'invalid_sso_token');
    }
  } else {
    claims = decodeJwtPayload(idToken);
    if (claims.exp && claims.exp * 1000 < Date.now()) {
      throw unauthorized('Google ID token expired', 'invalid_sso_token');
    }
  }
  if (!claims.sub || !claims.email) throw unauthorized('Google token has no subject/email', 'invalid_sso_token');
  return {
    subject: String(claims.sub),
    email: String(claims.email).toLowerCase(),
    displayName: claims.name || String(claims.email).split('@')[0],
    avatarUrl: claims.picture || null,
  };
}

export async function verifyFacebook(config, accessToken, fetchImpl = fetch) {
  if (!accessToken) throw badRequest('accessToken is required', 'validation_error');
  if (accessToken.startsWith('mock:')) return parseMockToken('facebook', accessToken);

  if (config.facebookAppId && config.facebookAppSecret) {
    const appToken = `${config.facebookAppId}|${config.facebookAppSecret}`;
    const res = await fetchImpl(
      `https://graph.facebook.com/debug_token?input_token=${encodeURIComponent(accessToken)}&access_token=${encodeURIComponent(appToken)}`,
    );
    const body = res.ok ? await res.json() : null;
    if (!body?.data?.is_valid || String(body.data.app_id) !== config.facebookAppId) {
      throw unauthorized('Facebook rejected the access token', 'invalid_sso_token');
    }
  }

  const res = await fetchImpl(
    `https://graph.facebook.com/me?fields=id,name,email,picture.type(large)&access_token=${encodeURIComponent(accessToken)}`,
  );
  if (!res.ok) throw unauthorized('Facebook rejected the access token', 'invalid_sso_token');
  const me = await res.json();
  if (!me.id) throw unauthorized('Facebook profile has no id', 'invalid_sso_token');
  return {
    subject: String(me.id),
    // Facebook accounts may have no e-mail (phone sign-up) - fall back to a stable placeholder.
    email: (me.email || `fb-${me.id}@facebook.invalid`).toLowerCase(),
    displayName: me.name || `Facebook user ${me.id}`,
    avatarUrl: me.picture?.data?.url || null,
  };
}
