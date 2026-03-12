// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

/**
 * Extracts a user-friendly error message from API errors.
 * OpenAPI clients often throw errors where the message contains:
 *   HTTP-Code: 409
 *   Message: Unknown API Status Code!
 *   Body: {"error": "DUPLICATE_COMMAND(...): Command submission already exists."}
 *   Headers: {...}
 * This extracts the actual error from the Body JSON and optionally the human-readable part.
 */
export function extractApiErrorMessage(err: unknown): string {
  const msg = typeof err === 'string' ? err : err instanceof Error ? err.message : String(err);

  let errorValue: string | null = null;

  // Try to extract error from Body - handle both quoted "{\"error\":\"...\"}" and raw JSON
  const bodyMatch = msg.match(/Body:\s*("?)(\{[\s\S]*?\})\s*(?:"|\s*(?:Headers:|$))/);
  if (bodyMatch) {
    const jsonStr =
      bodyMatch[1] === '"' ? bodyMatch[2].replace(/\\n/g, '\n').replace(/\\"/g, '"') : bodyMatch[2];
    try {
      const body = JSON.parse(jsonStr);
      if (typeof body?.error === 'string') {
        errorValue = body.error;
      }
    } catch {
      return msg;
    }
  }

  // Fallback: match "error": "..." or \"error\": \"...\" (escaped)
  if (!errorValue) {
    const errorMatch = msg.match(/(?:"|\\")error(?:"|\\")\s*:\s*(?:"|\\")((?:[^"\\]|\\.)*?)(?:"|\\")/);
    if (errorMatch) {
      errorValue = errorMatch[1].replace(/\\"/g, '"');
    }
  }

  if (errorValue) {
    // Extract human-readable part after "): " (e.g. "Command submission already exists" from "DUPLICATE_COMMAND(10,786e4738): Command submission already exists")
    const humanReadable = errorValue.match(/\):\s*(.+)$/);
    return humanReadable ? humanReadable[1].trim() : errorValue;
  }

  return msg;
}
