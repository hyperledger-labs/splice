// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { describeUnexpected, exhaust } from '../probe.ts';
import type { Check } from './index.ts';

const FAKE_IPS = ['203.0.113.7', '203.0.113.8', '203.0.113.9'];

const SPOOFED_HEADERS: Record<string, string> = {
  'x-forwarded-for': FAKE_IPS.join(', '),
  'x-real-ip': FAKE_IPS[0],
  forwarded: `for=${FAKE_IPS[0]}`,
  'x-envoy-external-address': FAKE_IPS[0],
};

const REQUESTS_PER_ATTEMPT = 20;
// Tokens refilled since the exhaust go to whichever requests arrive first, so all spoofed and
// control requests are interleaved and only a clear advantage for a header counts as a bypass.
const BYPASS_MARGIN = REQUESTS_PER_ATTEMPT / 2;

export const spoofingNotPossible: Check = {
  name: 'spoofing-not-possible',
  async run(probe, component) {
    const burst = await exhaust(probe, component.maxRequestsPerIp);
    if (!burst.exhausted) {
      return `limit not enforced, so spoofing cannot be assessed${describeUnexpected(burst)}`;
    }

    // Variant 0 is the control; the others each carry one spoofed header.
    const variants: [string, Record<string, string>][] = [
      ['control', {}],
      ...Object.entries(SPOOFED_HEADERS).map(
        ([header, value]): [string, Record<string, string>] => [header, { [header]: value }],
      ),
    ];
    const accepted = new Array<number>(variants.length).fill(0);
    const results = await Promise.all(
      Array.from({ length: REQUESTS_PER_ATTEMPT * variants.length }, (_, i) =>
        probe.send(1, variants[i % variants.length][1]),
      ),
    );
    results.forEach((tally, i) => (accepted[i % variants.length] += tally.accepted));

    const control = accepted[0];
    const bypassingHeaders = variants
      .map(([header], v) => ({ header, spoofed: accepted[v] }))
      .slice(1)
      .filter(({ spoofed }) => spoofed - control >= BYPASS_MARGIN)
      .map(
        ({ header, spoofed }) =>
          `${header} (${spoofed}/${REQUESTS_PER_ATTEMPT} spoofed vs ${control}/${REQUESTS_PER_ATTEMPT} control accepted)`,
      );
    return bypassingHeaders.length > 0
      ? `limit bypassed via ${bypassingHeaders.join(', ')}`
      : undefined;
  },
};
