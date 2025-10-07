// Copyright (c) 2025 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as gcp from '@pulumi/gcp';
import * as pulumi from '@pulumi/pulumi';

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
  rate: number; // Requests per minute
  interval: number; // Interval in seconds
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

/**
 * Creates a Cloud Armor security policy
 * @param name The name of the security policy
 * @param args Configuration for the security policy
 * @param opts Pulumi resource options
 * @returns The created security policy resource
 */
export function createCloudArmorPolicy(
  name: string,
  args: CloudArmorConfig,
  opts?: pulumi.ComponentResourceOptions
): gcp.compute.SecurityPolicy {
  const project = args.project;

  // Step 1: Create the security policy
  const securityPolicy = new gcp.compute.SecurityPolicy(
    name,
    {
      name: args.name,
      project,
      description: args.description || `Cloud Armor security policy for ${name}`,
    },
    opts
  );

  const ruleOpts = { ...opts, parent: securityPolicy };

  // Step 2: Add predefined WAF rules
  if (args.predefinedWafRules && args.predefinedWafRules.length > 0) {
    addPredefinedWafRules(securityPolicy, args.predefinedWafRules, project, ruleOpts);
  }

  // Step 3: Add IP whitelisting rules
  addIpWhitelistRules(securityPolicy, project, ruleOpts);

  // Step 4: Add throttling/banning rules for specific API endpoints
  if (args.apiThrottles && args.apiThrottles.length > 0) {
    addThrottleAndBanRules(securityPolicy, args.apiThrottles, project, ruleOpts);
  }

  // Step 5: Add default deny rule
  addDefaultDenyRule(securityPolicy, project, ruleOpts);

  return securityPolicy;
}

/**
 * Adds predefined WAF rules to a security policy
 */
function addPredefinedWafRules(
  securityPolicy: gcp.compute.SecurityPolicy,
  rules: PredefinedWafRule[],
  project?: string,
  opts?: pulumi.ResourceOptions
): void {
  // TODO (DACH-NY/canton-network-internal#406) implement
}

/**
 * Adds IP whitelisting rules to a security policy
 */
function addIpWhitelistRules(
  securityPolicy: gcp.compute.SecurityPolicy,
  project?: string,
  opts?: pulumi.ResourceOptions
): void {
  // TODO (DACH-NY/canton-network-internal#1250) implement
}

/**
 * Adds throttle and ban rules for API endpoints to a security policy
 */
function addThrottleAndBanRules(
  securityPolicy: gcp.compute.SecurityPolicy,
  apiThrottles: ApiThrottleConfig[],
  project?: string,
  opts?: pulumi.ResourceOptions
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
        project,
        description: `${action === 'throttle' ? 'Throttle' : 'Ban'} rule for ${endpoint.name} API endpoint`,
        priority: priority,
        action: action === 'ban' ? 'deny' : 'throttle',
        match: {
          expr: {
            expression: matchExpr,
          },
        },
        rateLimitOptions:
          action === 'throttle'
            ? {
                rateLimitThreshold: {
                  count: throttle.rate,
                  intervalSec: throttle.interval,
                },
                conformAction: 'allow',
                exceedAction: 'deny',
              }
            : undefined,
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
  project?: string,
  opts?: pulumi.ResourceOptions
): void {
  new gcp.compute.SecurityPolicyRule(
    'default-deny',
    {
      securityPolicy: securityPolicy.name,
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
    opts
  );
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
