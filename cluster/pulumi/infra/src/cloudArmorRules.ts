// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as _ from 'lodash';
import { WafRuleGroup } from '@canton-network/splice-pulumi-common/src/config/cloudArmorConfig';
import {
  extractPathPrefixes,
  PerEndpointLimits,
} from '@canton-network/splice-pulumi-common/src/ratelimit/envoyRateLimiter';
import { createHash } from 'crypto';

// limits from https://cloud.google.com/armor/quotas#limits, in which a
// "subexpression" is an arg to && or ||
export const MAX_SUBEXPRESSION_LENGTH = 1024;
export const MAX_EXPRESSION_LENGTH = 2048;

// https://cloud.google.com/armor/quotas#limits
export const MAX_IPS_PER_RULE = 10;
// the default quota is 200 rules per security policy; leave headroom for the
// endpoint, WAF and default rules
export const MAX_IP_WHITELIST_RULES = 150;

/**
 * Splits the whitelisted source IP ranges into per-rule chunks, since a single Cloud
 * Armor rule can only carry MAX_IPS_PER_RULE ranges.
 *
 * @param availablePriorities how many rule priority numbers are reserved for these rules
 */
export function ipWhitelistRuleChunks(ipRanges: string[], availablePriorities: number): string[][] {
  const unique = [...new Set(ipRanges)].sort();
  if (unique.length === 0) {
    throw new Error(
      'No whitelisted IP ranges for Cloud Armor: every internal endpoint would be denied'
    );
  }

  const chunks = _.chunk(unique, MAX_IPS_PER_RULE);
  if (chunks.length > MAX_IP_WHITELIST_RULES) {
    throw new Error(
      `${unique.length} whitelisted IP ranges need ${chunks.length} Cloud Armor rules, ` +
        `which exceeds the ${MAX_IP_WHITELIST_RULES} rule budget (max ${MAX_IPS_PER_RULE} ranges per rule). ` +
        `Consider using a network security address group instead.`
    );
  }
  if (chunks.length > availablePriorities) {
    throw new Error(
      `IP whitelist rules (${chunks.length}) would overlap the throttle rule priority range`
    );
  }
  return chunks;
}

/**
 * Picks the priority offset of the first of `ruleNames.length` consecutive rules, derived
 * deterministically from the rule names.
 *
 * Cloud Armor rejects a rule whose priority is still held by another rule, and Pulumi
 * gives no ordering guarantee between the deletes and creates of sibling rules. If
 * priorities were plain indexes, reordering, inserting or renaming rules would move an
 * existing priority to a different rule, so the new rule can be created while the old one
 * still holds that priority. Seeding with the names moves the whole block to fresh,
 * almost certainly unused, priorities whenever the set or order of the rules changes,
 * while unchanged rules keep their priorities.
 *
 * @param availablePriorities how many rule priority numbers are reserved for these rules
 */
export function seededPriorityOffset(ruleNames: string[], availablePriorities: number): number {
  const range = availablePriorities - ruleNames.length + 1;
  if (range <= 0) {
    throw new Error(
      `${ruleNames.length} rules do not fit into ${availablePriorities} rule priorities`
    );
  }
  // 48 bits are well within Number.MAX_SAFE_INTEGER
  const hash = createHash('sha256').update(JSON.stringify(ruleNames)).digest().readUIntBE(0, 6);
  return hash % range;
}

// the OWASP CRS version behind each Cloud Armor rule set generation, see
// https://cloud.google.com/armor/docs/waf-rules. It is part of the opt-out rule ids,
// and there is no way to derive it from the rule set name.
const OWASP_CRS_VERSIONS: Record<string, string> = {
  v33: 'v030301',
  v422: 'v042200',
};

type WafSignature = WafRuleGroup['signatures'][number];

/**
 * Expands a numeric OWASP CRS id into the full opt-out rule id Cloud Armor expects,
 * e.g. ('sqli-v422-stable', '942190') -> 'owasp-crs-v042200-id942190-sqli'.
 */
function optOutRuleId(signatureName: string, crsId: string): string {
  const match = /^(.*)-(v\d+)-stable$/.exec(signatureName);
  if (!match) {
    throw new Error(
      `Cannot expand opt-out rule id ${crsId}: ${signatureName} is not a versioned OWASP CRS rule set`
    );
  }
  const [, category, generation] = match;
  const crsVersion = OWASP_CRS_VERSIONS[generation];
  if (!crsVersion) {
    throw new Error(
      `Unknown OWASP CRS version for rule set ${signatureName}, add ${generation} to OWASP_CRS_VERSIONS`
    );
  }
  return `owasp-crs-${crsVersion}-id${crsId}-${category}`;
}

function wafSignatureCondition(context: string, signature: WafSignature): string {
  const options = [
    ...(signature.sensitivity !== undefined ? [`'sensitivity': ${signature.sensitivity}`] : []),
    ...(signature.optOutRuleIds && signature.optOutRuleIds.length > 0
      ? [
          `'opt_out_rule_ids': [${signature.optOutRuleIds
            .map(id => `'${optOutRuleId(signature.name, id)}'`)
            .join(', ')}]`,
        ]
      : []),
  ].join(', ');
  return checkSubexpressionLength(
    `${context}/${signature.name}`,
    `evaluatePreconfiguredWaf('${signature.name}', {${options}})`
  );
}

/**
 * Builds the match expression of a WAF rule: the request matches if any of the
 * group's signatures fires.
 *
 * @param excludedHostsExpr condition matching the hosts the WAF rules must not apply to
 */
export function wafRuleExpression(group: WafRuleGroup, excludedHostsExpr?: string): string {
  const signatureExpr = group.signatures
    .map(s => wafSignatureCondition(group.name, s))
    .join(' || ');
  const expr = excludedHostsExpr ? `!(${excludedHostsExpr}) && (${signatureExpr})` : signatureExpr;
  if (expr.length > MAX_EXPRESSION_LENGTH) {
    throw new Error(
      `Cloud Armor WAF expression for ${group.name} exceeds the ${MAX_EXPRESSION_LENGTH} character limit (current: ${expr.length}). ` +
        `Consider splitting the signatures across more rules.`
    );
  }
  return expr;
}

export function checkSubexpressionLength(context: string, expr: string): string {
  if (expr.length > MAX_SUBEXPRESSION_LENGTH) {
    throw new Error(
      `Cloud Armor subexpression for ${context} exceeds the ${MAX_SUBEXPRESSION_LENGTH} character limit (current: ${expr.length}): ${expr}`
    );
  }
  return expr;
}

/**
 * How a rule selects the hosts it applies to:
 * - `hostname`: one exact host
 * - `hostPrefixRegex`: an RE2 fragment matching the leading label(s) under the cluster
 *   DNS name, e.g. `scan` or `sequencer-[0-9]+`. `perNodeHost` adds the node label in
 *   between, i.e. `<prefix>.<node>.<cluster dns name>` instead of
 *   `<prefix>.<cluster dns name>`.
 */
export type HostMatch = { hostname: string } | { hostPrefixRegex: string; perNodeHost: boolean };

/**
 * Builds the host match condition of a rule, or undefined to match any host.
 *
 * Only `clusterHostname` is matched. A cluster resolves under a second DNS name too (see
 * getDnsNames), but everything is served under CLUSTER_HOSTNAME, so matching just that
 * one keeps the expressions short and the rules unambiguous.
 *
 * @param context config key of the rule, only used for error messages
 * @param clusterHostname the cluster's DNS name, i.e. CLUSTER_HOSTNAME
 * @param match which hosts the rule applies to, or undefined for all of them
 */
export function hostCondition(
  context: string,
  clusterHostname: string,
  match?: HostMatch
): string | undefined {
  if (!match) {
    return undefined;
  }
  const clusterHostRegex = _.escapeRegExp(clusterHostname.toLowerCase());
  const hostnameRegex =
    'hostname' in match
      ? _.escapeRegExp(match.hostname.toLowerCase())
      : [
          `(?:${match.hostPrefixRegex})`,
          ...(match.perNodeHost ? ['[\\w-]+'] : []),
          clusterHostRegex,
        ].join('\\.');
  // the host header may carry a port, and Cloud Armor does not strip it
  return checkSubexpressionLength(
    context,
    `request.headers['host'].lower().matches(R"^${hostnameRegex}(?::[0-9]+)?$")`
  );
}

/**
 * Builds the path match condition for an endpoint.
 *
 * When `restrictToRateLimitedPaths` is set, the condition is narrowed to exactly the
 * paths under `pathPrefix` that the envoy rate limit config knows about. That is only
 * correct for endpoints actually covered by those rate limits (scan and the token
 * registry); for anything else it must be false, otherwise the rule would match none of
 * the endpoint's real paths and the traffic would be denied by the default rule.
 *
 * @param context config key of the endpoint, only used for error messages
 */
export function allowedPathsCondition(
  context: string,
  scanExternalRateLimits: PerEndpointLimits,
  pathPrefix: string,
  restrictToRateLimitedPaths: boolean
): string {
  // normalize before matching, see
  // https://cloud.google.com/armor/docs/configure-security-policies#path-traversal-and-normalization
  const simplePrefixMatch = () =>
    checkSubexpressionLength(
      context,
      `request.path.lower().urlDecode().startsWith(R"${pathPrefix.toLowerCase()}")`
    );

  if (!restrictToRateLimitedPaths || _.isEmpty(scanExternalRateLimits.rateLimits)) {
    return simplePrefixMatch();
  }

  const basePrefix = pathPrefix.endsWith('/') ? pathPrefix : `${pathPrefix}/`;
  const dynamicPathRxs = extractPathPrefixes(scanExternalRateLimits.rateLimits)
    .filter(p => p.startsWith(basePrefix))
    .map(p => _.escapeRegExp(p.substring(basePrefix.length).toLowerCase()));

  if (dynamicPathRxs.length === 0) {
    // no rate limited path lives under this prefix, so there is nothing to narrow to
    return simplePrefixMatch();
  }

  const regexPattern = `${_.escapeRegExp(basePrefix.toLowerCase())}(?:${dynamicPathRxs.join('|')})`;
  const pathExpr = `request.path.lower().urlDecode().matches(R"^${regexPattern}")`;
  if (pathExpr.length > MAX_SUBEXPRESSION_LENGTH) {
    throw new Error(
      `Cloud Armor path expression for ${context} exceeds the ${MAX_SUBEXPRESSION_LENGTH} character limit (current: ${pathExpr.length}). ` +
        `Consider grouping path prefixes more aggressively.`
    );
  }
  return pathExpr;
}

/**
 * Combines the path and host conditions into the full match expression of a rule.
 */
export function matchExpression(
  context: string,
  pathExpr: string,
  hostExpr: string | undefined
): string {
  const expr = [pathExpr, ...(hostExpr ? [hostExpr] : [])].join(' && ');
  if (expr.length > MAX_EXPRESSION_LENGTH) {
    throw new Error(
      `Cloud Armor expression for ${context} exceeds the ${MAX_EXPRESSION_LENGTH} character limit (current: ${expr.length})`
    );
  }
  return expr;
}
