// Copyright (c) 2025 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import * as gcp from '@pulumi/gcp';
import * as pulumi from '@pulumi/pulumi';
import _ from 'lodash';

import { loadIPRanges } from './config';

// Rule number ranges
const PREDEFINED_WAF_RULE_MIN = 1;
const PREDEFINED_WAF_RULE_MAX = 10000;
const IP_WHITELIST_RULE_MIN = 10000;
const IP_WHITELIST_RULE_MAX = 100000000;
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
  rate: number;  // Requests per minute
  interval: number;  // Interval in seconds
}

export interface CloudArmorConfig {
  name: string;
  project?: string;
  description?: string;
  predefinedWafRules?: PredefinedWafRule[];
  apiThrottles?: ApiThrottleConfig[];
}

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

export class CloudArmorPolicy extends pulumi.ComponentResource {
  public readonly securityPolicy: gcp.compute.SecurityPolicy;

  constructor(
    name: string,
    args: CloudArmorConfig,
    opts?: pulumi.ComponentResourceOptions
  ) {
    super('splice:cloudarmor:SecurityPolicy', name, {}, opts);
    const project = args.project;

    // Step 1: Create the security policy
    this.securityPolicy = new gcp.compute.SecurityPolicy(
      name,
      {
        name: args.name,
        project,
        description: args.description || `Cloud Armor security policy for ${name}`,
      },
      { parent: this }
    );

    // Step 2: Add predefined WAF rules
    let ruleCounter = PREDEFINED_WAF_RULE_MIN;
    if (args.predefinedWafRules && args.predefinedWafRules.length > 0) {
      args.predefinedWafRules.forEach(rule => {
        const priority = rule.priority || ruleCounter;
        ruleCounter = priority + RULE_SPACING;

        if (priority >= PREDEFINED_WAF_RULE_MAX) {
          throw new Error(`Predefined WAF rule priority ${priority} exceeds maximum ${PREDEFINED_WAF_RULE_MAX}`);
        }

        const ruleName = `waf-${rule.name}`;
        new gcp.compute.SecurityPolicyRule(
          ruleName,
          {
            securityPolicy: this.securityPolicy.name,
            project,
            description: `Predefined WAF rule: ${rule.name}`,
            priority: priority,
            action: rule.action,
            preview: rule.preview || false,
            match: {
              expr: {
                expression: `evaluatePreconfiguredWaf('${rule.name}', {'sensitivity': '${rule.sensitivityLevel || 'medium'}'})`
              }
            },
          },
          { parent: this }
        );
      });
    }

    // Step 3: Add IP whitelisting rules
    const ipRanges = loadIPRanges();

    // IP ranges from loadIPRanges may exceed the limit of a single rule
    // Split into chunks of 100 IPs
    const chunkSize = 100;
    const ipChunks = _.chunk(ipRanges, chunkSize);

    let ipRuleCounter = IP_WHITELIST_RULE_MIN;
    ipChunks.forEach((ipChunk, index) => {
      const priority = ipRuleCounter;
      ipRuleCounter += RULE_SPACING;

      if (priority >= IP_WHITELIST_RULE_MAX) {
        throw new Error(`IP whitelist rule priority ${priority} exceeds maximum ${IP_WHITELIST_RULE_MAX}`);
      }

      new gcp.compute.SecurityPolicyRule(
        `ip-whitelist-${index}`,
        {
          securityPolicy: this.securityPolicy.name,
          project,
          description: `Allow traffic from whitelisted IP ranges (chunk ${index + 1})`,
          priority: priority,
          action: 'allow',
          match: {
            config: {
              srcIpRanges: ipChunk,
            },
          },
        },
        { parent: this }
      );
    });

    // Step 4: Add throttling/banning rules for specific API endpoints
    let throttleRuleCounter = THROTTLE_BAN_RULE_MIN;
    if (args.apiThrottles && args.apiThrottles.length > 0) {
      args.apiThrottles.forEach(apiConfig => {
        const priority = apiConfig.priority || throttleRuleCounter;
        throttleRuleCounter = priority + RULE_SPACING;

        if (priority >= THROTTLE_BAN_RULE_MAX) {
          throw new Error(`Throttle rule priority ${priority} exceeds maximum ${THROTTLE_BAN_RULE_MAX}`);
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
            securityPolicy: this.securityPolicy.name,
            project,
            description: `${action === 'throttle' ? 'Throttle' : 'Ban'} rule for ${endpoint.name} API endpoint`,
            priority: priority,
            action: action === 'ban' ? 'deny' : 'throttle',
            match: {
              expr: {
                expression: matchExpr,
              },
            },
            rateLimitOptions: action === 'throttle' ? {
              rateLimitThreshold: {
                count: throttle.rate,
                intervalSec: throttle.interval,
              },
              conformAction: 'allow',
              exceedAction: 'deny',
            } : undefined,
          },
          { parent: this }
        );
      });
    }

    // Step 5: Add default deny rule
    new gcp.compute.SecurityPolicyRule(
      'default-deny',
      {
        securityPolicy: this.securityPolicy.name,
        project,
        description: 'Default rule to deny all other traffic',
        priority: DEFAULT_DENY_RULE_NUMBER,
        action: 'deny',
        match: {
          config: {
            srcIpRanges: ['*'],
          },
        },
      },
      { parent: this }
    );

    this.registerOutputs({
      securityPolicy: this.securityPolicy,
    });
  }
}

// Helper function to create a Cloud Armor security policy
export function createCloudArmorPolicy(
  name: string,
  config: CloudArmorConfig,
  opts?: pulumi.ComponentResourceOptions
): CloudArmorPolicy {
  return new CloudArmorPolicy(name, config, opts);
}

/* sample invocation
const armorPolicy = createCloudArmorPolicy("my-policy", {
  name: "my-armor-policy",
  project: "my-gcp-project",
  description: "Security policy for my services",
  predefinedWafRules: [
    { name: "xss-stable", action: "deny", sensitivityLevel: "medium" }
  ],
  apiThrottles: [
    {
      endpoint: { name: "api-login", path: "/api/login", hostname: "myservice.example.com" },
      throttle: { rate: 10, interval: 60 },
      action: "throttle"
    }
  ]
});
 */
