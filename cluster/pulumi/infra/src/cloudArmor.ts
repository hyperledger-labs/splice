// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as gcp from '@pulumi/gcp';
import * as pulumi from '@pulumi/pulumi';
import * as _ from 'lodash';
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

export type CloudArmorConfig = config.CloudArmorConfig & {
  predefinedWafRules?: PredefinedWafRule[];
};

type ThrottleConfig = CloudArmorConfig['publicEndpoints'];

export interface PredefinedWafRule {
  name: string;
  action: 'allow' | 'deny' | 'throttle';
  priority?: number;
  preview?: boolean;
  sensitivityLevel?: 'off' | 'low' | 'medium' | 'high';
}

// Regional and Global policies and rules use different types/constructors; most
// of our pulumi code doesn't care about the difference so can use this alias
export type CloudArmorPolicy = gcp.compute.RegionSecurityPolicy;
const CloudArmorPolicy = gcp.compute.RegionSecurityPolicy;
const PolicyRule = gcp.compute.RegionSecurityPolicyRule;

/**
 * Creates a Cloud Armor security policy
 * @param cac loaded configuration
 * @param opts Pulumi resource options
 * @returns The created security policy resource, if enabled
 */
export function configureCloudArmorPolicy(
  cac: CloudArmorConfig,
  opts?: pulumi.ComponentResourceOptions
): CloudArmorPolicy | undefined {
  if (!cac.enabled) {
    return undefined;
  }

  // Step 1: Create the security policy
  const name = `waf-whitelist-throttle-ban-${CLUSTER_BASENAME}`;
  const securityPolicy = new CloudArmorPolicy(
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

  const ruleOpts = { ...opts, parent: securityPolicy, deletedWith: securityPolicy };

  // Step 2: Add predefined WAF rules
  if (cac.predefinedWafRules && cac.predefinedWafRules.length > 0) {
    addPredefinedWafRules(/*securityPolicy, args.predefinedWafRules, cac.allRulesPreviewOnly, ruleOpts*/);
  }

  // Step 3: Add IP whitelisting rules
  addIpWhitelistRules(/*securityPolicy, cac.allRulesPreviewOnly, ruleOpts*/);

  // Step 4: Add throttling/banning rules for specific API endpoints
  if (cac.publicEndpoints && !_.isEmpty(cac.publicEndpoints)) {
    addThrottleAndBanRules(securityPolicy, cac.publicEndpoints, cac.allRulesPreviewOnly, ruleOpts);
  }

  // Step 5: Add default deny rule
  addDefaultDenyRule(securityPolicy, cac.allRulesPreviewOnly, ruleOpts);

  return securityPolicy;
}

/**
 * Adds predefined WAF rules to a security policy
 */
function addPredefinedWafRules(): void {
  /*
  securityPolicy: Policy,
  rules: PredefinedWafRule[],
  preview: boolean,
  opts: pulumi.ResourceOptions
     */
  // TODO (DACH-NY/canton-network-internal#406) implement
}

/**
 * Adds IP whitelisting rules to a security policy
 */
function addIpWhitelistRules(): void {
  /*
  securityPolicy: Policy,
  preview: boolean,
  opts: pulumi.ResourceOptions
     */
  // TODO (DACH-NY/canton-network-internal#1250) implement
}

/**
 * Adds throttle and ban rules for API endpoints to a security policy
 */
function addThrottleAndBanRules(
  securityPolicy: CloudArmorPolicy,
  throttles: ThrottleConfig,
  preview: boolean,
  opts: pulumi.ResourceOptions
): void {
  _.sortBy(Object.entries(throttles), e => e[0]).reduce(
    (priority, [confEntryHead, singleServiceThrottle]) => {
      if (priority >= THROTTLE_BAN_RULE_MAX) {
        throw new Error(
          `Throttle rule priority ${priority} exceeds maximum ${THROTTLE_BAN_RULE_MAX}`
        );
      }

      const { hostname, pathPrefix, throttleAcrossAllEndpointsAllIps } = singleServiceThrottle;
      // leave out the rule but consume the priority number if max is 0
      // this makes the pulumi update cleaner if toggling just one service
      if (throttleAcrossAllEndpointsAllIps.maxRequestsBeforeHttp429 > 0) {
        const ruleName = `throttle-all-endpoints-all-ips-${confEntryHead}`;

        // Build the expression for path and hostname matching
        const pathExpr = `request.path.startsWith(R"${pathPrefix}")`;
        const hostExpr = `request.headers['host'].matches(R"^${_.escapeRegExp(hostname)}(?::[0-9]+)?$")`;
        const matchExpr = `${pathExpr} && ${hostExpr}`;

        new PolicyRule(
          ruleName,
          {
            securityPolicy: securityPolicy.name,
            region: securityPolicy.region,
            description: `Throttle rule for all ${confEntryHead} API endpoints`,
            priority,
            preview: preview || singleServiceThrottle.rulePreviewOnly,
            action: 'throttle',
            match: {
              expr: {
                expression: matchExpr,
              },
            },
            rateLimitOptions: {
              enforceOnKey: 'ALL',
              rateLimitThreshold: {
                count: throttleAcrossAllEndpointsAllIps.maxRequestsBeforeHttp429,
                intervalSec: throttleAcrossAllEndpointsAllIps.withinIntervalSeconds,
              },
              conformAction: 'allow',
              exceedAction: 'deny(429)', // 429 Too Many Requests
            },
          },
          opts
        );
      }
      return priority + RULE_SPACING;
    },
    THROTTLE_BAN_RULE_MIN
  );
}

/**
 * Adds a default deny rule to a security policy
 */
function addDefaultDenyRule(
  securityPolicy: CloudArmorPolicy,
  preview: boolean,
  opts: pulumi.ResourceOptions
): void {
  // when you create a SecurityPolicy it has a default allow rule; we assume
  // that if you want all rules in preview, you *also* still want to allow
  // all traffic
  if (preview) {
    return;
  }
  new PolicyRule(
    'default-deny',
    {
      securityPolicy: securityPolicy.name,
      region: securityPolicy.region,
      description: 'Default rule to deny all other traffic',
      priority: DEFAULT_DENY_RULE_NUMBER,
      // default rule cannot be in preview mode; google API gives 400 if you try
      preview: false,
      action: 'deny',
      match: {
        versionedExpr: 'SRC_IPS_V1',
        config: {
          srcIpRanges: ['*'],
        },
      },
    },
    opts
  );
}
