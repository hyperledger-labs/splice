// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { WafRuleGroupsSchema } from '@canton-network/splice-pulumi-common/src/config/cloudArmorConfig';
import { expect, test, describe } from '@jest/globals';
import fs from 'fs';
import yaml from 'js-yaml';
import path from 'path';

import {
  allowedPathsCondition,
  hostCondition,
  ipWhitelistRuleChunks,
  matchExpression,
  MAX_EXPRESSION_LENGTH,
  MAX_IPS_PER_RULE,
  MAX_IP_WHITELIST_RULES,
  MAX_SUBEXPRESSION_LENGTH,
  seededPriorityOffset,
  wafRuleExpression,
} from './cloudArmorRules';

const clusterHostname = 'scratchd.network.canton.global';
const otherDnsName = 'scratchd.global.canton.network.digitalasset.com';

const scanRateLimits = {
  rateLimits: {
    '/api/scan/v0/dso': { name: 'dso', type: 'limited' },
    '/registry/metadata/v1/info': { name: 'info', type: 'limited' },
    // not externally reachable, so extractPathPrefixes drops it
    '/api/sv/v0/dso': { name: 'sv-dso', type: 'limited' },
  },
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
} as any;

// mirrors how Cloud Armor evaluates `matches`: an unanchored RE2 search on the
// normalized value, with our expressions anchoring explicitly via ^
function matchesFromExpr(expr: string): (value: string) => boolean {
  const asMatches =
    /^request\.(?:headers\['host'\]|path)\.lower\(\)(?:\.urlDecode\(\))?\.matches\(R"(.*)"\)$/.exec(
      expr
    );
  if (asMatches) {
    const re = new RegExp(asMatches[1]);
    return value => re.test(value.toLowerCase());
  }
  const asStartsWith = /^request\.path\.lower\(\)\.urlDecode\(\)\.startsWith\(R"(.*)"\)$/.exec(
    expr
  );
  if (asStartsWith) {
    return value => value.toLowerCase().startsWith(asStartsWith[1]);
  }
  throw new Error(`unrecognized expression: ${expr}`);
}

describe('hostCondition', () => {
  test('matches per-node hosts on the cluster DNS name, with and without a port', () => {
    const expr = hostCondition('publicScan', clusterHostname, {
      hostPrefixRegex: 'scan',
      perNodeHost: true,
    })!;
    const matches = matchesFromExpr(expr);

    expect(matches('scan.sv-2.scratchd.network.canton.global')).toBe(true);
    expect(matches('scan.sv-2.scratchd.network.canton.global:443')).toBe(true);
    expect(matches(`scan.sv-2.${otherDnsName}`)).toBe(false);
    expect(matches('SCAN.sv-2.scratchd.network.canton.global')).toBe(true);

    expect(matches('sv.sv-2.scratchd.network.canton.global')).toBe(false);
    expect(matches('scan.sv-2.scratcha.network.canton.global')).toBe(false);
    // the escaped dots must not act as wildcards
    expect(matches('scan.sv-2.scratchdXnetwork.canton.global')).toBe(false);
    // must be fully anchored
    expect(matches('evil.scan.sv-2.scratchd.network.canton.global')).toBe(false);
    expect(matches('scan.sv-2.scratchd.network.canton.global.evil.com')).toBe(false);
  });

  test('sequencer prefix regex matches all migration ids, but not the P2P API', () => {
    const matches = matchesFromExpr(
      hostCondition('sequencer', clusterHostname, {
        hostPrefixRegex: 'sequencer-[0-9]+',
        perNodeHost: true,
      })!
    );

    expect(matches('sequencer-0.sv-1.scratchd.network.canton.global')).toBe(true);
    expect(matches('sequencer-12.sv-1.scratchd.network.canton.global')).toBe(true);
    expect(matches('sequencer-12.sv-1.scratchd.network.canton.global:443')).toBe(true);

    // the P2P API has no rule of its own: peer SVs are covered by the IP whitelist
    expect(matches('sequencer-p2p-3.sv-1.scratchd.network.canton.global')).toBe(false);
    expect(matches('sequencer.sv-1.scratchd.network.canton.global')).toBe(false);
    expect(matches('scan.sv-1.scratchd.network.canton.global')).toBe(false);
  });

  test('exact hostname is anchored and regex-escaped', () => {
    const matches = matchesFromExpr(
      hostCondition('publicScan', clusterHostname, {
        hostname: 'scan.sv-2.scratchd.network.canton.global',
      })!
    );
    expect(matches('scan.sv-2.scratchd.network.canton.global')).toBe(true);
    expect(matches('scan.sv-2.scratchdXnetwork.canton.global')).toBe(false);
    expect(matches('scan.sv-3.scratchd.network.canton.global')).toBe(false);
  });

  test('is undefined when neither hostname nor prefix is given', () => {
    expect(hostCondition('anything', clusterHostname)).toBeUndefined();
  });
});

describe('allowedPathsCondition', () => {
  test('narrows to the rate limited paths under the prefix', () => {
    const matches = matchesFromExpr(
      allowedPathsCondition('publicScan', scanRateLimits, '/api/scan', true)
    );

    expect(matches('/api/scan/v0/dso')).toBe(true);
    expect(matches('/api/scan/v0/dso/extra')).toBe(true);
    expect(matches('/api/scan/v0/not-rate-limited')).toBe(false);
    expect(matches('/registry/metadata/v1/info')).toBe(false);
  });

  test('narrows the token registry to its own paths', () => {
    const matches = matchesFromExpr(
      allowedPathsCondition('tokenRegistry', scanRateLimits, '/registry', true)
    );
    expect(matches('/registry/metadata/v1/info')).toBe(true);
    expect(matches('/api/scan/v0/dso')).toBe(false);
  });

  test('does not narrow when restrictToRateLimitedPaths is false', () => {
    // regression: the scan/registry rate limit prefixes all start with "/", so
    // narrowing a "/" prefix used to produce a scan-only regex that matched none of
    // the sequencer's gRPC paths, silently denying all sequencer traffic
    const matches = matchesFromExpr(allowedPathsCondition('sequencer', scanRateLimits, '/', false));

    expect(matches('/com.digitalasset.canton.sequencer.api.v30.SequencerService/Subscribe')).toBe(
      true
    );
    expect(matches('/api/scan/v0/dso')).toBe(true);
  });

  test('falls back to a prefix match when no rate limited path is under the prefix', () => {
    const matches = matchesFromExpr(
      allowedPathsCondition('other', scanRateLimits, '/api/sv', true)
    );
    expect(matches('/api/sv/v0/dso')).toBe(true);
    expect(matches('/api/scan/v0/dso')).toBe(false);
  });

  test('throws when the generated path expression is too long', () => {
    const manyPaths = Object.fromEntries(
      Array.from({ length: 200 }, (_unused, i) => [
        `/api/scan/v0/endpoint-with-a-fairly-long-name-${i}`,
        { name: `e${i}`, type: 'limited' },
      ])
    );
    expect(() =>
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      allowedPathsCondition('publicScan', { rateLimits: manyPaths } as any, '/api/scan', true)
    ).toThrow(new RegExp(`exceeds the ${MAX_SUBEXPRESSION_LENGTH} character limit`));
  });
});

describe('matchExpression', () => {
  test('combines path and host conditions', () => {
    expect(matchExpression('publicScan', 'PATH', 'HOST')).toBe('PATH && HOST');
  });

  test('omits the host condition when there is none', () => {
    expect(matchExpression('publicScan', 'PATH', undefined)).toBe('PATH');
  });
});

describe('ipWhitelistRuleChunks', () => {
  const ips = (n: number) => Array.from({ length: n }, (_unused, i) => `10.0.${i}.0/24`);

  test('respects the per-rule IP range limit', () => {
    const chunks = ipWhitelistRuleChunks(ips(25), 100);
    expect(chunks).toHaveLength(3);
    expect(chunks[0]).toHaveLength(MAX_IPS_PER_RULE);
    expect(chunks[2]).toHaveLength(5);
    expect(chunks.flat().sort()).toEqual(ips(25).sort());
  });

  test('deduplicates and sorts so rule contents are stable across runs', () => {
    expect(ipWhitelistRuleChunks(['10.0.2.0/24', '10.0.1.0/24', '10.0.2.0/24'], 100)).toEqual([
      ['10.0.1.0/24', '10.0.2.0/24'],
    ]);
  });

  test('refuses an empty whitelist rather than locking everything out', () => {
    expect(() => ipWhitelistRuleChunks([], 100)).toThrow(/every internal endpoint would be denied/);
  });

  test('refuses to exceed the per-policy rule budget', () => {
    expect(() =>
      ipWhitelistRuleChunks(ips(MAX_IP_WHITELIST_RULES * MAX_IPS_PER_RULE + 1), 100000)
    ).toThrow(/exceeds the 150 rule budget/);
  });

  test('refuses to overlap the throttle rule priority range', () => {
    expect(() => ipWhitelistRuleChunks(ips(50), 2)).toThrow(
      /would overlap the throttle rule priority range/
    );
  });
});

describe('wafRuleExpression', () => {
  const wafRuleGroups = WafRuleGroupsSchema.parse(
    yaml.load(fs.readFileSync(path.resolve(__dirname, 'cloudArmorRules.test.yaml'), 'utf8'))
  );
  const expressions = wafRuleGroups.map(g => wafRuleExpression(g));

  test('matches the tuning validated on the DA-1 SV and DA-Wallet validator', () => {
    expect(expressions).toEqual([
      "evaluatePreconfiguredWaf('sqli-v422-stable', {'sensitivity': 1, 'opt_out_rule_ids': ['owasp-crs-v042200-id942100-sqli', 'owasp-crs-v042200-id942190-sqli', 'owasp-crs-v042200-id942240-sqli', 'owasp-crs-v042200-id942270-sqli', 'owasp-crs-v042200-id942290-sqli', 'owasp-crs-v042200-id942320-sqli', 'owasp-crs-v042200-id942350-sqli', 'owasp-crs-v042200-id942500-sqli', 'owasp-crs-v042200-id942560-sqli']}) || " +
        "evaluatePreconfiguredWaf('sessionfixation-v422-stable', {'sensitivity': 1}) || " +
        "evaluatePreconfiguredWaf('generic-v422-stable', {'opt_out_rule_ids': ['owasp-crs-v042200-id934150-generic', 'owasp-crs-v042200-id934170-generic']})",
      "evaluatePreconfiguredWaf('xss-v422-stable', {'sensitivity': 1, 'opt_out_rule_ids': ['owasp-crs-v042200-id941100-xss', 'owasp-crs-v042200-id941190-xss', 'owasp-crs-v042200-id941200-xss', 'owasp-crs-v042200-id941210-xss', 'owasp-crs-v042200-id941220-xss', 'owasp-crs-v042200-id941230-xss', 'owasp-crs-v042200-id941240-xss', 'owasp-crs-v042200-id941250-xss', 'owasp-crs-v042200-id941260-xss', 'owasp-crs-v042200-id941270-xss', 'owasp-crs-v042200-id941280-xss', 'owasp-crs-v042200-id941290-xss', 'owasp-crs-v042200-id941300-xss']}) || " +
        "evaluatePreconfiguredWaf('lfi-v422-stable', {'sensitivity': 1, 'opt_out_rule_ids': ['owasp-crs-v042200-id930110-lfi']}) || " +
        "evaluatePreconfiguredWaf('rce-v422-stable', {'sensitivity': 1, 'opt_out_rule_ids': ['owasp-crs-v042200-id932120-rce', 'owasp-crs-v042200-id932125-rce', 'owasp-crs-v042200-id932140-rce', 'owasp-crs-v042200-id932370-rce', 'owasp-crs-v042200-id932380-rce']})",
      "evaluatePreconfiguredWaf('protocolattack-v422-stable', {'sensitivity': 1, 'opt_out_rule_ids': ['owasp-crs-v042200-id921110-protocolattack', 'owasp-crs-v042200-id921150-protocolattack']}) || " +
        "evaluatePreconfiguredWaf('java-v422-stable', {'sensitivity': 1}) || " +
        "evaluatePreconfiguredWaf('cve-canary', {'sensitivity': 1}) || " +
        "evaluatePreconfiguredWaf('nodejs-v33-stable', {'sensitivity': 1})",
    ]);
  });

  test('stays within the Cloud Armor expression length limits', () => {
    expressions.forEach(expr => {
      expect(expr.length).toBeLessThanOrEqual(MAX_EXPRESSION_LENGTH);
      expr.split(' || ').forEach(sub => {
        expect(sub.length).toBeLessThanOrEqual(MAX_SUBEXPRESSION_LENGTH);
      });
    });
  });

  test('excludes the grafana host from every WAF rule', () => {
    const excluded = hostCondition('waf-excluded-hosts', clusterHostname, {
      hostPrefixRegex: 'grafana',
      perNodeHost: false,
    })!;
    const matches = matchesFromExpr(excluded);
    expect(matches('grafana.scratchd.network.canton.global')).toBe(true);
    expect(matches('grafana.scratchd.network.canton.global:443')).toBe(true);
    expect(matches('grafanax.scratchd.network.canton.global')).toBe(false);
    expect(matches('sv.scratchd.network.canton.global')).toBe(false);
    expect(matches('grafana.sv.scratchd.network.canton.global')).toBe(false);

    wafRuleGroups.forEach((group, i) => {
      const expr = wafRuleExpression(group, excluded);
      expect(expr).toBe(`!(${excluded}) && (${expressions[i]})`);
      expect(expr.length).toBeLessThanOrEqual(MAX_EXPRESSION_LENGTH);
    });
  });

  test('applies no host exclusion when none is configured', () => {
    expect(wafRuleExpression(wafRuleGroups[0], undefined)).toBe(expressions[0]);
  });

  test('has unique rule names', () => {
    const names = wafRuleGroups.map(g => g.name);
    expect(new Set(names).size).toBe(names.length);
  });

  test('throws when a group exceeds the expression length limit', () => {
    expect(() =>
      wafRuleExpression({
        name: 'too-long',
        description: 'too long',
        signatures: Array.from({ length: 10 }, () => ({
          name: 'sqli-v33-stable',
          sensitivity: 1,
          optOutRuleIds: Array.from({ length: 10 }, (_unused, i) => `9421${i}0`),
        })),
      })
    ).toThrow(new RegExp(`exceeds the ${MAX_EXPRESSION_LENGTH} character limit`));
  });
});

describe('seededPriorityOffset', () => {
  const available = 100_000_000;
  const priorities = (names: string[]) => {
    const first = seededPriorityOffset(names, available);
    return new Map(names.map((n, i) => [n, first + i]));
  };

  test('is deterministic and keeps all rules within the available priorities', () => {
    const names = ['a', 'b', 'c'];
    const first = seededPriorityOffset(names, available);
    expect(seededPriorityOffset([...names], available)).toBe(first);
    expect(first).toBeGreaterThanOrEqual(0);
    expect(first + names.length - 1).toBeLessThan(available);
    expect(seededPriorityOffset(names, names.length)).toBe(0);
  });

  test('gives every rule a fresh priority when rules are reordered, added or renamed', () => {
    const before = priorities(['a', 'b', 'c']);
    for (const after of [
      ['b', 'a', 'c'],
      ['a', 'x', 'b', 'c'],
      ['a', 'b'],
      ['a', 'b', 'd'],
    ]) {
      const old = new Set(before.values());
      for (const p of priorities(after).values()) {
        expect(old.has(p)).toBe(false);
      }
    }
  });

  test('rejects more rules than available priorities', () => {
    expect(() => seededPriorityOffset(['a', 'b', 'c'], 2)).toThrow();
  });
});
