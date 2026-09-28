import express from 'express';
import { z } from 'zod';
import { toUserDto } from '../auth.js';
import { parseBody } from '../http.js';

// Same rule as the legacy app: international format, "+" and 10-13 digits.
export const PHONE_REGEX = /^\+[0-9]{10,13}$/;

const patchSchema = z.object({
  displayName: z.string().trim().min(1).max(80).optional(),
  sosContacts: z.array(z.string().trim().regex(PHONE_REGEX, 'phone numbers must look like +36301234567')).max(10).optional(),
  sosMessage: z.string().trim().max(300).optional(),
});

export function meRouter({ db }) {
  const router = express.Router();

  router.get('/', (req, res) => res.json(toUserDto(req.user)));

  router.patch('/', (req, res) => {
    const body = parseBody(patchSchema, req.body);
    Object.assign(req.user, body);
    db.save();
    res.json(toUserDto(req.user));
  });

  return router;
}
