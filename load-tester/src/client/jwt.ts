// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import crypto from 'k6/crypto';
import encoding from 'k6/encoding';

function sign(data: string, secret: string) {
  const hasher = crypto.createHMAC('sha256', secret);
  hasher.update(data);

  // Some manual base64 rawurl encoding as `Hasher.digest(encodingType)`
  // doesn't support that encoding type yet.
  return hasher.digest('base64').replace(/\//g, '_').replace(/\+/g, '-').replace(/=/g, '');
}

export function encodeJwtHmac256<T extends object>(data: T, secret: string): string {
  const header = encoding.b64encode(JSON.stringify({ typ: 'JWT', alg: 'HS256' }), 'rawurl');
  const payload = encoding.b64encode(JSON.stringify(data), 'rawurl');
  const sig = sign(header + '.' + payload, secret);
  return [header, payload, sig].join('.');
}
