/** Error with an HTTP status and a stable machine readable code: {"error":{"code","message"}}. */
export class ApiError extends Error {
  constructor(status, code, message) {
    super(message);
    this.status = status;
    this.code = code;
  }
}

export const badRequest = (message, code = 'bad_request') => new ApiError(400, code, message);
export const unauthorized = (message = 'Authentication required', code = 'unauthorized') => new ApiError(401, code, message);
export const forbidden = (message = 'Forbidden') => new ApiError(403, 'forbidden', message);
export const notFound = (message = 'Not found') => new ApiError(404, 'not_found', message);
export const conflict = (message, code = 'conflict') => new ApiError(409, code, message);

/** Wraps an async route handler so rejected promises reach the error middleware. */
export const wrap = (fn) => (req, res, next) => Promise.resolve(fn(req, res, next)).catch(next);

/** Parses req.body with a zod schema, throwing a 400 with the first issue on failure. */
export function parseBody(schema, body) {
  const result = schema.safeParse(body ?? {});
  if (!result.success) {
    const issue = result.error.issues[0];
    const field = issue.path.join('.') || 'body';
    throw badRequest(`${field}: ${issue.message}`, 'validation_error');
  }
  return result.data;
}
