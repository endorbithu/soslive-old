import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import express from 'express';
import multer from 'multer';
import { z } from 'zod';
import { badRequest, forbidden, notFound, parseBody, wrap } from '../http.js';

export const EVENT_TYPES = ['SOS', 'LIVE', 'PHOTO'];

const createSchema = z.object({
  type: z.enum(EVENT_TYPES),
  lat: z.number().min(-90).max(90).optional(),
  lng: z.number().min(-180).max(180).optional(),
});

const locationSchema = z.object({
  lat: z.number().min(-90).max(90),
  lng: z.number().min(-180).max(180),
  accuracy: z.number().nonnegative().optional(),
});

const commentSchema = z.object({
  message: z.string().trim().min(1).max(1000),
});

/**
 * Stream key, modelled on the legacy "{Ymd}_{company}_{username}_{id}_{hash}" format.
 * RTMP_STREAM_KEY overrides it with one fixed key (e.g. YouTube / Facebook Live).
 */
export function streamTargetFor(config, event) {
  if (event.type === 'PHOTO') return null;
  let key = config.rtmpStreamKey;
  if (!key) {
    const date = event.createdAt.slice(0, 10).replaceAll('-', '');
    const hash = crypto
      .createHmac('sha256', config.jwtSecret)
      .update(`${event.userId}|${event.id}`)
      .digest('hex')
      .slice(0, 16);
    key = `${config.rtmpKeyPrefix}${date}_${event.userId}_${event.id}_${hash}`;
  }
  return { url: config.rtmpUrl, streamKey: key, publishUrl: `${config.rtmpUrl}/${key}` };
}

export function shareUrlFor(config, event) {
  return `${config.publicWebUrl}/e/${event.id}`;
}

export function toEventDto(config, db, event) {
  const last = event.locations.at(-1) ?? null;
  return {
    id: event.id,
    type: event.type,
    status: event.status,
    createdAt: event.createdAt,
    stoppedAt: event.stoppedAt ?? null,
    lastLocation: last,
    shareUrl: shareUrlFor(config, event),
    stream: streamTargetFor(config, event),
    commentCount: db.commentsForEvent(event.id).length,
    photoCount: db.photosForEvent(event.id).length,
  };
}

export function toCommentDto(comment) {
  return {
    id: comment.id,
    eventId: comment.eventId,
    authorName: comment.authorName,
    authorType: comment.authorType,
    message: comment.message,
    createdAt: comment.createdAt,
  };
}

export function toPhotoDto(config, photo) {
  return {
    id: photo.id,
    eventId: photo.eventId,
    url: `${config.publicWebUrl}/uploads/${photo.filename}`,
    createdAt: photo.createdAt,
  };
}

export function eventsRouter({ config, db }) {
  const router = express.Router();

  fs.mkdirSync(config.uploadDir, { recursive: true });
  const upload = multer({
    storage: multer.diskStorage({
      destination: config.uploadDir,
      filename: (_req, file, cb) => {
        const ext = path.extname(file.originalname || '').toLowerCase() || '.jpg';
        cb(null, `${Date.now()}-${crypto.randomBytes(6).toString('hex')}${ext}`);
      },
    }),
    limits: { fileSize: 20 * 1024 * 1024 },
    fileFilter: (_req, file, cb) => cb(null, /^image\//.test(file.mimetype)),
  });

  const ownEvent = (req) => {
    const event = db.findEvent(Number(req.params.id));
    if (!event) throw notFound('Event not found');
    if (event.userId !== req.user.id) throw forbidden('This event belongs to another user');
    return event;
  };

  router.post('/', (req, res) => {
    const body = parseBody(createSchema, req.body);
    const now = new Date().toISOString();
    const event = db.insertEvent({
      userId: req.user.id,
      type: body.type,
      status: body.type === 'PHOTO' ? 'OPEN' : 'LIVE',
      createdAt: now,
      stoppedAt: null,
      locations: body.lat != null && body.lng != null ? [{ lat: body.lat, lng: body.lng, at: now }] : [],
    });
    res.status(201).json(toEventDto(config, db, event));
  });

  router.get('/', (req, res) => {
    const items = db.events
      .filter((e) => e.userId === req.user.id)
      .sort((a, b) => b.id - a.id)
      .map((e) => toEventDto(config, db, e));
    res.json({ items });
  });

  router.get('/:id', (req, res) => res.json(toEventDto(config, db, ownEvent(req))));

  router.post('/:id/location', (req, res) => {
    const event = ownEvent(req);
    const body = parseBody(locationSchema, req.body);
    event.locations.push({ lat: body.lat, lng: body.lng, at: new Date().toISOString() });
    db.save();
    res.json(toEventDto(config, db, event));
  });

  router.post('/:id/stop', (req, res) => {
    const event = ownEvent(req);
    if (event.status !== 'STOPPED') {
      event.status = 'STOPPED';
      event.stoppedAt = new Date().toISOString();
      db.save();
    }
    res.json(toEventDto(config, db, event));
  });

  router.get('/:id/comments', (req, res) => {
    const event = ownEvent(req);
    const sinceId = Number(req.query.sinceId || 0);
    const all = db.commentsForEvent(event.id);
    res.json({ total: all.length, items: all.filter((c) => c.id > sinceId).map(toCommentDto) });
  });

  router.post('/:id/comments', (req, res) => {
    const event = ownEvent(req);
    const body = parseBody(commentSchema, req.body);
    const comment = db.insertComment({
      eventId: event.id,
      userId: req.user.id,
      authorName: req.user.displayName,
      authorType: 'OWNER',
      message: body.message,
    });
    res.status(201).json(toCommentDto(comment));
  });

  router.get('/:id/photos', (req, res) => {
    const event = ownEvent(req);
    res.json({ items: db.photosForEvent(event.id).map((p) => toPhotoDto(config, p)) });
  });

  router.post('/:id/photos', (req, res, next) => {
    try {
      ownEvent(req);
    } catch (e) {
      return next(e);
    }
    return upload.single('photo')(req, res, (err) => {
      if (err) return next(badRequest(err.message, 'upload_failed'));
      if (!req.file) return next(badRequest('Multipart field "photo" with an image is required', 'validation_error'));
      const photo = db.insertPhoto({ eventId: Number(req.params.id), filename: req.file.filename, size: req.file.size });
      return res.status(201).json(toPhotoDto(config, photo));
    });
  });

  return router;
}

/** Minimal public event page - the link that goes out in the SOS SMS. Lets "viewers" leave comments. */
export function publicRouter({ config, db }) {
  const router = express.Router();
  const esc = (s) => String(s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);

  router.get('/:id', (req, res, next) => {
    const event = db.findEvent(Number(req.params.id));
    if (!event) return next(notFound('Event not found'));
    const owner = db.findUserById(event.userId);
    const last = event.locations.at(-1);
    const comments = db.commentsForEvent(event.id);
    const photos = db.photosForEvent(event.id);
    res.type('html').send(`<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>SOSlive #${event.id}</title>
<style>body{font-family:system-ui,sans-serif;max-width:640px;margin:0 auto;padding:16px}img{max-width:100%}li{margin:6px 0}</style></head><body>
<h1>SOSlive #${event.id} – ${esc(event.type)} (${esc(event.status)})</h1>
<p>${esc(owner?.displayName ?? 'unknown')} · ${esc(event.createdAt)}</p>
${last ? `<p>Last location: <a href="https://maps.google.com/?q=${last.lat},${last.lng}">${last.lat}, ${last.lng}</a> (${esc(last.at)})</p>` : '<p>No location yet.</p>'}
${photos.map((p) => `<img src="/uploads/${esc(p.filename)}" alt="photo">`).join('')}
<h2>Comments (${comments.length})</h2><ul>${comments.map((c) => `<li><b>${esc(c.authorName)}</b> ${esc(c.createdAt)}<br>${esc(c.message)}</li>`).join('')}</ul>
<form method="post" action="/e/${event.id}/comments"><input name="name" placeholder="Name" required maxlength="80">
<textarea name="message" placeholder="Message" required maxlength="1000"></textarea><button>Send</button></form>
</body></html>`);
  });

  router.post('/:id/comments', express.urlencoded({ extended: false }), (req, res, next) => {
    const event = db.findEvent(Number(req.params.id));
    if (!event) return next(notFound('Event not found'));
    const name = String(req.body.name || '').trim().slice(0, 80);
    const message = String(req.body.message || '').trim().slice(0, 1000);
    if (!name || !message) return next(badRequest('name and message are required'));
    db.insertComment({ eventId: event.id, userId: null, authorName: name, authorType: 'VIEWER', message });
    res.redirect(303, `/e/${event.id}`);
  });

  return router;
}
