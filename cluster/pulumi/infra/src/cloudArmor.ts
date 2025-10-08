// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as gcp from '@pulumi/gcp';
import * as pulumi from '@pulumi/pulumi';
import { CLUSTER_BASENAME } from '@lfdecentralizedtrust/splice-pulumi-common';

import * as config from './config';

// Rule number ranges
const THROTTLE_BAN_RULE_MIN = 100000000;
const THROTTLE_BAN_RULE_MAX = 200000000;
const DEFAULT_DENY_RULE_NUMBER = 2147483647;
const RULE_SPACING = 100;

// Types for API endpoint throttling/banning configuration
export interface ApiEndpoint {
  name: string;
  path: string;
  hostname: string;
}

export interface ThrottleConfig {
  perIp: boolean;
  rate: number; // Requests per minute
  interval: number; // Interval in seconds
}

export type CloudArmorConfig = Pick<config.CloudArmorConfig, 'enabled' | 'allRulesPreviewOnly'> & {
  predefinedWafRules?: PredefinedWafRule[];
  apiThrottles?: ApiThrottleConfig[];
};

export interface PredefinedWafRule {
  name: string;
  action: 'allow' | 'deny' | 'throttle';
  priority?: number;
  preview?: boolean;
  sensitivityLevel?: 'off' | 'low' | 'medium' | 'high';
}

export interface ApiThrottleConfig {
  endpoint: ApiEndpoint;
  throttle: ThrottleConfig;
  action: 'throttle' | 'ban';
  priority?: number;
}

/**
 * Creates a Cloud Armor security policy
 * @param cac loaded configuration
 * @param opts Pulumi resource options
 * @returns The created security policy resource, if enabled
 */
export function configureCloudArmorPolicy(
  cac: CloudArmorConfig,
  opts?: pulumi.ComponentResourceOptions
): gcp.compute.SecurityPolicy | undefined {
  if (!cac.enabled) {
    return undefined;
  }

  // Step 1: Create the security policy
  const name = `waf-whitelist-throttle-ban-${CLUSTER_BASENAME}`;
  const securityPolicy = new gcp.compute.SecurityPolicy(
    name,
    {
      name,
      description: `Cloud Armor security policy for ${CLUSTER_BASENAME}`,
      type: 'CLOUD_ARMOR', // attachable to backend service only
      // using `rules` to define all rules at once would be fewer Pulumi resources,
      // but the preview would entail changing this array if the rules were changed,
      // making those changes harder to review than with the separate resources
    },
    opts
  );

  const ruleOpts = { ...opts, parent: securityPolicy };

  // Step 2: Add predefined WAF rules
  if (cac.predefinedWafRules && cac.predefinedWafRules.length > 0) {
    addPredefinedWafRules(/*securityPolicy, args.predefinedWafRules, ruleOpts*/);
  }

  // Step 3: Add IP whitelisting rules
  addIpWhitelistRules(/*securityPolicy, ruleOpts*/);

  // Step 4: Add throttling/banning rules for specific API endpoints
  if (cac.apiThrottles && cac.apiThrottles.length > 0) {
    addThrottleAndBanRules(securityPolicy, cac.apiThrottles, ruleOpts);
  }

  // Step 5: Add default deny rule
  addDefaultDenyRule(securityPolicy, ruleOpts);

  return securityPolicy;
}

/**
 * Adds predefined WAF rules to a security policy
 */
function addPredefinedWafRules(): void {
  /*
  securityPolicy: gcp.compute.SecurityPolicy,
  rules: PredefinedWafRule[],
  opts: pulumi.ResourceOptions
     */
  // TODO (DACH-NY/canton-network-internal#406) implement
}

/**
 * Adds IP whitelisting rules to a security policy
 */
function addIpWhitelistRules(): void {
  /*
  securityPolicy: gcp.compute.SecurityPolicy,
  opts: pulumi.ResourceOptions
     */
  // TODO (DACH-NY/canton-network-internal#1250) implement
}

/**
 * Adds throttle and ban rules for API endpoints to a security policy
 */
function addThrottleAndBanRules(
  securityPolicy: gcp.compute.SecurityPolicy,
  apiThrottles: ApiThrottleConfig[],
  opts: pulumi.ResourceOptions
): void {
  let throttleRuleCounter = THROTTLE_BAN_RULE_MIN;

  apiThrottles.forEach(apiConfig => {
    const priority = apiConfig.priority || throttleRuleCounter;
    throttleRuleCounter = priority + RULE_SPACING;

    if (priority >= THROTTLE_BAN_RULE_MAX) {
      throw new Error(
        `Throttle rule priority ${priority} exceeds maximum ${THROTTLE_BAN_RULE_MAX}`
      );
    }

    const { endpoint, throttle, action } = apiConfig;
    const ruleName = `${action}-${endpoint.name}`;

    // Build the expression for path and hostname matching
    const pathExpr = `request.path.matches('${endpoint.path}')`;
    const hostExpr = `request.headers['host'].matches('${endpoint.hostname}')`;
    const matchExpr = `${pathExpr} && ${hostExpr}`;

    new gcp.compute.SecurityPolicyRule(
      ruleName,
      {
        securityPolicy: securityPolicy.name,
        description: `${action === 'throttle' ? 'Throttle' : 'Ban'} rule${throttle.perIp ? ' per-IP' : ''} for ${endpoint.name} API endpoint`,
        priority: priority,
        action: action === 'ban' ? 'rate_based_ban' : 'throttle',
        match: {
          expr: {
            expression: matchExpr,
          },
        },
        rateLimitOptions: {
          // ban point is banThreshold + ratelimit count; consider splitting up rather than doubling
          ...(action === 'ban'
            ? {
                banDurationSec: 600,
                banThreshold: {
                  count: throttle.rate,
                  intervalSec: throttle.interval,
                },
              }
            : {}),
          enforceOnKey: throttle.perIp ? 'IP' : 'ALL',
          rateLimitThreshold: {
            count: throttle.rate,
            intervalSec: throttle.interval,
          },
          conformAction: 'allow',
          exceedAction: 'deny(429)', // 429 Too Many Requests
        },
      },
      opts
    );
  });
}

/**
 * Adds a default deny rule to a security policy
 */
function addDefaultDenyRule(
  securityPolicy: gcp.compute.SecurityPolicy,
  opts: pulumi.ResourceOptions
): void {
  new gcp.compute.SecurityPolicyRule(
    'default-deny',
    {
      securityPolicy: securityPolicy.name,
      description: 'Default rule to deny all other traffic',
      priority: DEFAULT_DENY_RULE_NUMBER,
      action: 'deny',
      match: {
        config: {
          srcIpRanges: ['*'],
        },
      },
    },
    opts
  );
}
