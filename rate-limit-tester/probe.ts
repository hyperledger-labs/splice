// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import http from 'k6/http';
import grpc from 'k6/net/grpc';

import type { Component } from './config.ts';

export type Headers = Record<string, string>;

type Outcome = 'accepted' | 'rateLimited' | `unexpected ${string}`;

export type Tally = {
  accepted: number;
  rateLimited: number;
  unexpected: Record<string, number>;
};

export type Probe = {
  send(count: number, headers?: Headers): Promise<Tally>;
};

const REQUEST_TIMEOUT = '10s';
const PARALLEL_REQUESTS = 100;
// A burst straddling Envoy's timer-based refill can legitimately see twice the limit.
const EXHAUST_CAP_FACTOR = 2.5;

export function probeFor(component: Component): Probe {
  const sendOne =
    component.protocol === 'http'
      ? httpSender(component.endpoint)
      : grpcHealthSender(component.endpoint);
  return {
    async send(count, headers = {}) {
      const outcomes = await Promise.all(Array.from({ length: count }, () => sendOne(headers)));
      return outcomes.reduce(record, emptyTally());
    },
  };
}

function httpSender(url: string): (headers: Headers) => Promise<Outcome> {
  return async headers => {
    const response = await http.asyncRequest('GET', url, null, {
      headers,
      timeout: REQUEST_TIMEOUT,
    });
    if (response.status === 429) return 'rateLimited';
    if ((response.status >= 200 && response.status < 300) || response.status === 503) {
      return 'accepted';
    }
    return `unexpected http ${response.status || response.error_code}`;
  };
}

function grpcHealthSender(address: string): (headers: Headers) => Promise<Outcome> {
  const client = new grpc.Client();
  client.load([], 'health.proto');
  let connection: 'pending' | 'open' | `unexpected ${string}` = 'pending';

  return async headers => {
    if (connection === 'pending') {
      try {
        client.connect(address, { timeout: REQUEST_TIMEOUT });
        connection = 'open';
      } catch (error) {
        connection = `unexpected grpc connect failure: ${String(error)}`;
      }
    }
    if (connection !== 'open') return connection;
    try {
      const response = await client.asyncInvoke(
        'grpc.health.v1.Health/Check',
        {},
        { metadata: headers, timeout: REQUEST_TIMEOUT },
      );
      if (response.status === grpc.StatusOK) return 'accepted';
      if (response.status === grpc.StatusResourceExhausted) return 'rateLimited';
      return `unexpected grpc ${response.status}`;
    } catch (error) {
      return `unexpected grpc ${String(error)}`;
    }
  };
}

export type Exhaustion = Tally & { exhausted: boolean; seconds: number };

export async function exhaust(probe: Probe, limit: number): Promise<Exhaustion> {
  const startedAt = Date.now();
  const cap = Math.ceil(limit * EXHAUST_CAP_FACTOR);
  let total = emptyTally();
  while (total.rateLimited === 0 && sentCount(total) < cap) {
    total = merge(total, await probe.send(PARALLEL_REQUESTS));
  }
  return {
    ...total,
    exhausted: total.rateLimited > 0,
    seconds: (Date.now() - startedAt) / 1000,
  };
}

export function unexpectedCount(tally: Tally): number {
  return Object.values(tally.unexpected).reduce((sum, count) => sum + count, 0);
}

export function sentCount(tally: Tally): number {
  return tally.accepted + tally.rateLimited + unexpectedCount(tally);
}

export function describeUnexpected(tally: Tally): string {
  const entries = Object.entries(tally.unexpected);
  if (entries.length === 0) return '';
  return ` (${entries.map(([outcome, count]) => `${count}× ${outcome}`).join(', ')})`;
}

function emptyTally(): Tally {
  return { accepted: 0, rateLimited: 0, unexpected: {} };
}

function record(tally: Tally, outcome: Outcome): Tally {
  if (outcome === 'accepted') tally.accepted++;
  else if (outcome === 'rateLimited') tally.rateLimited++;
  else tally.unexpected[outcome] = (tally.unexpected[outcome] ?? 0) + 1;
  return tally;
}

function merge(a: Tally, b: Tally): Tally {
  const unexpected = { ...a.unexpected };
  for (const [outcome, count] of Object.entries(b.unexpected)) {
    unexpected[outcome] = (unexpected[outcome] ?? 0) + count;
  }
  return {
    accepted: a.accepted + b.accepted,
    rateLimited: a.rateLimited + b.rateLimited,
    unexpected,
  };
}
