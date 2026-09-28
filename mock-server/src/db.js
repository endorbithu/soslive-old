import fs from 'node:fs';
import path from 'node:path';

const EMPTY = () => ({
  seq: { user: 0, event: 0, comment: 0, photo: 0 },
  users: [],
  refreshTokens: [],
  events: [],
  comments: [],
  photos: [],
});

/**
 * Tiny JSON-file "database". Good enough for a simulated backend - everything lives in memory
 * and is flushed to disk after each mutation. Use file ":memory:" to skip persistence (tests).
 */
export class Db {
  constructor(file) {
    this.file = file;
    this.data = EMPTY();
    if (file !== ':memory:' && fs.existsSync(file)) {
      this.data = { ...EMPTY(), ...JSON.parse(fs.readFileSync(file, 'utf8')) };
    }
  }

  nextId(kind) {
    this.data.seq[kind] += 1;
    return this.data.seq[kind];
  }

  save() {
    if (this.file === ':memory:') return;
    fs.mkdirSync(path.dirname(this.file), { recursive: true });
    const tmp = `${this.file}.tmp`;
    fs.writeFileSync(tmp, JSON.stringify(this.data, null, 2));
    fs.renameSync(tmp, this.file);
  }

  // --- users ---
  get users() { return this.data.users; }
  findUserById(id) { return this.users.find((u) => u.id === id); }
  findUserByEmail(email) {
    const e = email.toLowerCase();
    return this.users.find((u) => u.email === e);
  }
  findUserByProvider(provider, subject) {
    return this.users.find((u) => u.providers?.[provider] === subject);
  }
  insertUser(user) {
    const row = {
      id: this.nextId('user'),
      email: user.email.toLowerCase(),
      displayName: user.displayName,
      avatarUrl: user.avatarUrl ?? null,
      passwordHash: user.passwordHash ?? null,
      providers: user.providers ?? {},
      sosContacts: [],
      sosMessage: '',
      createdAt: new Date().toISOString(),
    };
    this.users.push(row);
    this.save();
    return row;
  }

  // --- refresh tokens ---
  addRefreshToken(token, userId, expiresAt) {
    this.data.refreshTokens.push({ token, userId, expiresAt });
    this.save();
  }
  takeRefreshToken(token) {
    const idx = this.data.refreshTokens.findIndex((t) => t.token === token);
    if (idx < 0) return null;
    const [row] = this.data.refreshTokens.splice(idx, 1);
    this.save();
    return row.expiresAt > Date.now() ? row : null;
  }
  revokeRefreshToken(token) {
    this.data.refreshTokens = this.data.refreshTokens.filter((t) => t.token !== token);
    this.save();
  }

  // --- events ---
  get events() { return this.data.events; }
  findEvent(id) { return this.events.find((e) => e.id === id); }
  insertEvent(event) {
    const row = { id: this.nextId('event'), ...event };
    this.events.push(row);
    this.save();
    return row;
  }

  // --- comments ---
  commentsForEvent(eventId) { return this.data.comments.filter((c) => c.eventId === eventId); }
  insertComment(comment) {
    const row = { id: this.nextId('comment'), createdAt: new Date().toISOString(), ...comment };
    this.data.comments.push(row);
    this.save();
    return row;
  }

  // --- photos ---
  photosForEvent(eventId) { return this.data.photos.filter((p) => p.eventId === eventId); }
  insertPhoto(photo) {
    const row = { id: this.nextId('photo'), createdAt: new Date().toISOString(), ...photo };
    this.data.photos.push(row);
    this.save();
    return row;
  }
}
