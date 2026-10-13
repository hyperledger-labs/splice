// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { z } from 'zod';

// Single source of truth for the `cloudArmor` cluster config. The infra stack creates the
// Cloud Armor policy from it, the observability stack uses it to set up the related alerts.

/**
 * One of Cloud Armor's preconfigured WAF rule sets (see
 * https://cloud.google.com/armor/docs/waf-rules), with the individual OWASP CRS
 * signatures we opt out of.
 */
const WafSignatureSchema = z.object({
  // preconfigured rule set name, e.g. 'sqli-v422-stable'
  name: z.string(),
  // https://cloud.google.com/armor/docs/rule-tuning#sensitivity_levels: 1 only
  // evaluates the paranoia level 1 signatures, which are the ones least prone to
  // false positives. If unset, Cloud Armor's default (all levels) applies.
  sensitivity: z.number().int().min(0).max(4).optional(),
  // numeric OWASP CRS ids of the signatures to skip, e.g. '942190' for
  // 'owasp-crs-v042200-id942190-sqli'. These are the signatures that produced false
  // positives on our own traffic.
  optOutRuleIds: z.array(z.string().regex(/^[0-9]+$/, 'numeric OWASP CRS id')).default([]),
});

export const WafRuleGroupSchema = z.object({
  name: z.string().min(1),
  description: z.string(),
  signatures: z.array(WafSignatureSchema).min(1),
});

export const WafRuleGroupsSchema = z
  .array(WafRuleGroupSchema)
  .refine(
    groups => new Set(groups.map(g => g.name)).size === groups.length,
    'WAF rule group names must be unique, they are used as the Cloud Armor rule names'
  );

export type WafRuleGroup = z.infer<typeof WafRuleGroupSchema>;

// https://cloud.google.com/armor/docs/rate-limiting-overview: intervalSec only
// accepts this fixed set of values, anything else is rejected by the GCP API.
const cloudArmorIntervalSeconds = [
  10, 30, 60, 120, 180, 240, 300, 600, 900, 1200, 1800, 2700, 3600,
];
// https://cloud.google.com/armor/docs/rate-limiting-overview: threshold count max
const cloudArmorMaxRateLimitCount = 1000000;

export const CloudArmorLoggingConfigSchema = z.object({
  enabled: z.boolean().default(true),
  sampleRate: z.number().min(0).max(1).default(1),
  // reduces log volume significantly
  excludeAcceptedRequests: z.boolean().default(true),
});

export type CloudArmorLoggingConfig = z.infer<typeof CloudArmorLoggingConfigSchema>;

const CloudArmorWafRulesConfigSchema = z.object({
  enabled: z.boolean().default(true),
  groups: WafRuleGroupsSchema.default([]),
  // Leading hostname labels (`<prefix>.<cluster dns name>`) whose traffic the WAF rules
  // skip. Grafana is excluded because its dashboard JSON and query payloads regularly
  // trip the OWASP CRS signatures, and it is only reachable from whitelisted IPs anyway.
  excludedHostPrefixes: z.array(z.string().regex(/^[A-Za-z0-9-]+$/, 'DNS label')).default([]),
  // The preconfigured WAF rules deny by default, but are kept in Cloud Armor preview
  // mode so they only produce logs and alerts. That gives us attack detection and the
  // data to spot false positives before we let them block real traffic.
  previewOnly: z.boolean().default(true),
});

export const CloudArmorConfigSchema = z.object({
  enabled: z.boolean(),
  // "preview" is not pulumi preview, but https://cloud.google.com/armor/docs/security-policy-overview#preview_mode
  allRulesPreviewOnly: z.boolean(),
  logging: CloudArmorLoggingConfigSchema.prefault({}),
  wafRules: CloudArmorWafRulesConfigSchema.prefault({}),
  publicEndpoints: z
    .object({})
    .catchall(
      z
        .object({
          rulePreviewOnly: z.boolean().default(false),
          // exact hostname to match; mutually exclusive with hostPrefixRegex
          hostname: z
            .string()
            .regex(/^[A-Za-z0-9_-]+(\.[A-Za-z0-9_-]+)*$/, 'valid DNS hostname')
            .optional(),
          // expanded to `<hostPrefixRegex>.<node>.<cluster dns name>` for every DNS name
          // the cluster is served under. E.g. `scan` or `sequencer-[0-9]+`.
          // Mutually exclusive with hostname; if neither is given, any host matches.
          hostPrefixRegex: z.string().optional(),
          pathPrefix: z.string().regex(/^\/[^"]*$/, 'HTTP request path starting with /'),
          // when true, the rule only matches the subset of paths under pathPrefix that
          // are known. Must be false
          // for endpoints whose paths are not part of that config (e.g. the sequencer
          // gRPC APIs).
          restrictToRateLimitedPaths: z.boolean().default(true),
          // Per source IP throttling across all endpoints under this rule.
          //
          // Cloud Armor applies at most one rate limit per request: rules are evaluated
          // in priority order and the first match wins, and a request under the
          // threshold of a throttle rule takes its conformAction (always `allow`),
          // which ends policy evaluation. So a global (enforceOnKey: ALL) rule and a
          // per IP rule cannot both apply to the same traffic. We rate limit per IP
          // here, since that is what stops an abusive source at the edge without one
          // client being able to exhaust a bucket shared with everyone else; the global
          // cap lives in envoy instead (see sv.scan.externalRateLimits.globalLimits),
          // which is closer to the backend it protects.
          //
          // When omitted the endpoint is allowed without any Cloud Armor rate limiting.
          throttleAcrossAllEndpointsPerIp: z
            .object({
              withinIntervalSeconds: z
                .number()
                .refine(
                  n => cloudArmorIntervalSeconds.includes(n),
                  `must be one of ${cloudArmorIntervalSeconds.join(', ')}`
                ),
              maxRequestsBeforeHttp429: z
                .number()
                .int()
                .min(0, '0 to disallow requests or positive to allow')
                .max(cloudArmorMaxRateLimitCount),
            })
            .optional(),
        })
        .refine(
          e => !(e.hostname && e.hostPrefixRegex),
          'at most one of hostname and hostPrefixRegex may be set'
        )
        .refine(
          e => !(e.restrictToRateLimitedPaths && e.pathPrefix === '/'),
          'restrictToRateLimitedPaths must be false when pathPrefix is /'
        )
    )
    .default({}),
});

export type CloudArmorConfig = z.infer<typeof CloudArmorConfigSchema>;
