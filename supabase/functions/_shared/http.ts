// Uniform responses and error codes (PROTOCOL §9).

export type ErrorCode =
  | "bad_request"
  | "unauthenticated"
  | "forbidden"
  | "not_found"
  | "replay"
  | "expired"
  | "bad_signature"
  | "rate_limited"
  | "conflict"
  | "internal";

const STATUS: Record<ErrorCode, number> = {
  bad_request: 400,
  unauthenticated: 401,
  forbidden: 403,
  not_found: 404,
  replay: 409,
  expired: 410,
  bad_signature: 401,
  rate_limited: 429,
  conflict: 409,
  internal: 500,
};

// The ESP32 sends x-wolf-* headers; the app sends authorization and apikey.
export const CORS = {
  "access-control-allow-origin": "*",
  "access-control-allow-headers":
    "authorization, apikey, content-type, x-wolf-device, x-wolf-timestamp, x-wolf-nonce, x-wolf-signature",
  "access-control-allow-methods": "POST, OPTIONS",
};

export function json(body: unknown, status = 200, extra: HeadersInit = {}): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json", ...CORS, ...extra },
  });
}

/** An error the caller is allowed to see. Never embed key material in `message`. */
export class WolfError extends Error {
  constructor(public code: ErrorCode, message: string) {
    super(message);
  }
  toResponse(): Response {
    return json({ error: { code: this.code, message: this.message } }, STATUS[this.code]);
  }
}

export function fail(code: ErrorCode, message: string): never {
  throw new WolfError(code, message);
}

/**
 * Wrap a handler so that every path returns protocol-shaped JSON and an
 * unexpected throw can never leak a stack trace to a device on the internet.
 */
export function handler(fn: (req: Request) => Promise<Response>) {
  return async (req: Request): Promise<Response> => {
    if (req.method === "OPTIONS") return new Response("ok", { headers: CORS });
    if (req.method !== "POST") {
      return new WolfError("bad_request", "POST only").toResponse();
    }
    try {
      return await fn(req);
    } catch (err) {
      if (err instanceof WolfError) return err.toResponse();
      console.error("unhandled", err instanceof Error ? err.message : String(err));
      return new WolfError("internal", "internal error").toResponse();
    }
  };
}

export function clientIp(req: Request): string | null {
  const fwd = req.headers.get("x-forwarded-for");
  return fwd ? fwd.split(",")[0].trim() : null;
}
