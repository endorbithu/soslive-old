import { createApp, DEMO_USER } from './app.js';
import { loadConfig, validateConfig } from './config.js';

const config = loadConfig();
const errors = validateConfig(config);
if (errors.length) {
  for (const e of errors) console.error(`[config] ${e}`);
  process.exit(1);
}

if (!process.env.JWT_SECRET) {
  console.warn('[config] JWT_SECRET not set - using a random one, sessions will not survive a restart.');
}

createApp(config).listen(config.port, '0.0.0.0', () => {
  console.log(`SOSlive mock backend on http://0.0.0.0:${config.port}`);
  console.log(`  RTMP ingest : ${config.rtmpUrl}${config.rtmpStreamKey ? ' (fixed stream key)' : ' (per-event stream keys)'}`);
  console.log(`  Google SSO  : ${config.googleClientId ? 'verified' : 'simulated'}`);
  console.log(`  Facebook SSO: ${config.facebookAppId && config.facebookAppSecret ? 'verified' : 'simulated'}`);
  if (config.seedDemoUser) console.log(`  Demo login  : ${DEMO_USER.email} / ${DEMO_USER.password}`);
});
