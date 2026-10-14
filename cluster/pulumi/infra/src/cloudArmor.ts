// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as gcp from '@pulumi/gcp';
import * as pulumi from '@pulumi/pulumi';
import * as _ from 'lodash';
import {
  CLOUD_ARMOR_POLICY_NAME,
  CLOUD_ARMOR_RULE_GROUP_SIZE,
  cloudArmorRulePriority,
  CLUSTER_BASENAME,
  CLUSTER_HOSTNAME,
  lbRequestLogResourceTypesFilter,
} from '@canton-network/splice-pulumi-common';
import {
  CloudArmorConfig,
  WafRuleGroup,
} from '@canton-network/splice-pulumi-common/src/config/cloudArmorConfig';
import { PerEndpointLimits } from '@canton-network/splice-pulumi-common/src/ratelimit/envoyRateLimiter';

import {
  allowedPathsCondition,
  hostCondition,
  ipWhitelistRuleChunks,
  matchExpression,
  seededPriorityOffset,
  wafRuleExpression,
} from './cloudArmorRules';
import { loadIPRanges } from './whitelisting/ipRanges';

const DEFAULT_DENY_RULE_NUMBER = 2147483647;
const PREVIEW_DENY_RULE_NUMBER = DEFAULT_DENY_RULE_NUMBER - 1;

type ThrottleConfig = CloudArmorConfig['publicEndpoints'];

// Regional and Global policies and rules use different types/constructors; most
// of our pulumi code doesn't care about the difference so can use this alias
export type CloudArmorPolicy = gcp.compute.RegionSecurityPolicy;
const CloudArmorPolicy = gcp.compute.RegionSecurityPolicy;
const PolicyRule = gcp.compute.RegionSecurityPolicyRule;

/**
 * Creates a Cloud Armor security policy
 * @param cac loaded configuration
 * @param scanExternalRateLimits Envoy rate limit config
 * @param opts Pulumi resource options
 * @returns The created security policy resource, if enabled
 */
export function configureCloudArmorPolicy(
  cac: CloudArmorConfig,
  scanExternalRateLimits: PerEndpointLimits,
  opts?: pulumi.ComponentResourceOptions
): CloudArmorPolicy | undefined {
  if (!cac.enabled) {
    return undefined;
  }

  // Step 1: Create the security policy
  const name = CLOUD_ARMOR_POLICY_NAME;
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
    {
      ...opts,
      // All rules are managed as separate PolicyRule resources below, which use the
      // per-rule addRule/patchRule/removeRule endpoints. The policy resource itself
      // still reads the full rule list back from GCP, so a refresh imports the rules
      // into this resource's inputs and Pulumi then wants to "update" the policy.
      // That update is a PATCH on the parent policy which always carries the whole
      // rule list, and GCP rejects it once the policy is attached to a load balancer
      // backend and contains a deny rule:
      //   Error 400: HTTP references are not supported for security policies with deny rules.
      // Ignoring `rules` here keeps the parent policy free of spurious updates.
      ignoreChanges: [...(opts?.ignoreChanges ?? []), 'rules'],
    }
  );

  const ruleOpts = {
    ...opts,
    parent: securityPolicy,
    deletedWith: securityPolicy,
    deleteBeforeReplace: true,
  };

  // Step 2: Add predefined WAF rules
  if (cac.wafRules.enabled) {
    addWafRules(
      securityPolicy,
      cac.wafRules.groups,
      cac.wafRules.excludedHostPrefixes,
      cac.allRulesPreviewOnly || cac.wafRules.previewOnly,
      ruleOpts
    );
  }

  // Step 3: Add IP whitelisting rules
  addIpWhitelistRules(securityPolicy, cac.allRulesPreviewOnly, ruleOpts);

  // Step 4: Add throttling/banning rules for specific API endpoints
  if (cac.publicEndpoints && !_.isEmpty(cac.publicEndpoints)) {
    addThrottleAndBanRules(
      securityPolicy,
      cac.publicEndpoints,
      scanExternalRateLimits,
      cac.allRulesPreviewOnly,
      ruleOpts
    );
  }

  // Step 5: Add default deny rule
  addDefaultDenyRule(securityPolicy, cac.allRulesPreviewOnly, ruleOpts);

  if (cac.logging.enabled && cac.logging.excludeAcceptedRequests) {
    excludeAcceptedRequestLogs(securityPolicy, opts);
  }

  return securityPolicy;
}

function excludeAcceptedRequestLogs(
  securityPolicy: CloudArmorPolicy,
  opts?: pulumi.ComponentResourceOptions
): gcp.logging.ProjectExclusion {
  const name = `cloud-armor-accepted-requests-${CLUSTER_BASENAME}`;
  return new gcp.logging.ProjectExclusion(
    name,
    {
      name,
      description: `Drops the load balancer request logs of requests accepted by the Cloud Armor policy ${CLOUD_ARMOR_POLICY_NAME}`,
      filter: [
        lbRequestLogResourceTypesFilter(),
        `jsonPayload.enforcedSecurityPolicy.name="${CLOUD_ARMOR_POLICY_NAME}"`,
        // drop only explicit accepts, so that any other outcome is kept
        'jsonPayload.enforcedSecurityPolicy.outcome="ACCEPT"',
        '(NOT jsonPayload.previewSecurityPolicy:* OR jsonPayload.previewSecurityPolicy.outcome="ACCEPT")',
      ].join('\n'),
    },
    { ...opts, parent: securityPolicy }
  );
}

/**
 * Adds the preconfigured (OWASP CRS based) WAF rules to a security policy.
 *
 * They sit at the lowest priority numbers, so they are evaluated before the IP
 * whitelist and endpoint rules: an attack payload should be caught no matter which
 * host, path or source IP it comes from.
 */
function addWafRules(
  securityPolicy: CloudArmorPolicy,
  groups: WafRuleGroup[],
  excludedHostPrefixes: string[],
  preview: boolean,
  opts: pulumi.ResourceOptions
): void {
  const excludedHostsExpr =
    excludedHostPrefixes.length > 0
      ? hostCondition('waf-excluded-hosts', CLUSTER_HOSTNAME, {
          hostPrefixRegex: excludedHostPrefixes.map(p => p.toLowerCase()).join('|'),
          perNodeHost: false,
        })
      : undefined;
  const firstOffset = seededPriorityOffset(
    groups.map(g => g.name),
    CLOUD_ARMOR_RULE_GROUP_SIZE
  );
  groups.forEach((group, i) => {
    const priority = cloudArmorRulePriority('waf', firstOffset + i);
    new PolicyRule(
      group.name,
      {
        securityPolicy: securityPolicy.name,
        region: securityPolicy.region,
        description: group.description,
        priority,
        preview,
        action: 'deny(502)',
        match: {
          expr: {
            expression: wafRuleExpression(group, excludedHostsExpr),
          },
        },
      },
      opts
    );
  });
}

/**
 * Allows the internal and SV whitelisted source IPs to reach any host and path.
 *
 * These rules sit at a lower priority number than the public endpoint rules, so they
 * are evaluated first: whitelisted traffic is allowed outright and never throttled.
 *
 * Without them, everything served through the L7 gateway that is not listed in
 * `publicEndpoints` (grafana, prometheus, alertmanager, the SV and wallet UIs, docs,
 * /version, ...) would be dropped by the default deny rule. Cloud Armor runs in front
 * of istio and knows nothing about the istio AuthorizationPolicy whitelisting, which
 * still applies afterwards for the fine grained per-host rules.
 */
function addIpWhitelistRules(
  securityPolicy: CloudArmorPolicy,
  preview: boolean,
  opts: pulumi.ResourceOptions
): void {
  // only the internal and SV whitelists, not the full set of external ranges
  loadIPRanges(true).apply(ranges => {
    const chunks = ipWhitelistRuleChunks(ranges, CLOUD_ARMOR_RULE_GROUP_SIZE);

    return chunks.map(
      (chunk, i) =>
        new PolicyRule(
          `ip-whitelist-${i}`,
          {
            securityPolicy: securityPolicy.name,
            region: securityPolicy.region,
            description: `Allow whitelisted source IPs (${i + 1} of ${chunks.length})`,
            priority: cloudArmorRulePriority('ipWhitelist', i),
            preview,
            action: 'allow',
            match: {
              versionedExpr: 'SRC_IPS_V1',
              config: {
                srcIpRanges: chunk,
              },
            },
          },
          opts
        )
    );
  });
}

/**
 * Adds allow/throttle rules for the publicly reachable API endpoints. Any endpoint
 * without a matching rule here is blocked by the default deny rule.
 *
 * Throttling is enforced per source IP. Cloud Armor only ever applies one rate limit
 * rule per request (first match by priority wins, and conforming requests are allowed
 * outright), so a global cap cannot be stacked on top of these rules; it is enforced by
 * envoy instead.
 */
function addThrottleAndBanRules(
  securityPolicy: CloudArmorPolicy,
  throttles: ThrottleConfig,
  scanExternalRateLimits: PerEndpointLimits,
  preview: boolean,
  opts: pulumi.ResourceOptions
): void {
  const rules = _.sortBy(Object.entries(throttles), e => e[0]).map(
    ([confEntryHead, singleServiceThrottle]) => ({
      confEntryHead,
      singleServiceThrottle,
      ruleName:
        singleServiceThrottle.throttleAcrossAllEndpointsPerIp !== undefined
          ? `throttle-all-endpoints-per-ip-${confEntryHead}`
          : `allow-all-endpoints-all-ips-${confEntryHead}`,
    })
  );
  // skipped rules are part of the seed too, so toggling one does not move the others
  const firstOffset = seededPriorityOffset(
    rules.map(r => r.ruleName),
    CLOUD_ARMOR_RULE_GROUP_SIZE
  );
  rules.forEach(({ confEntryHead, singleServiceThrottle, ruleName }, i) => {
    const priority = cloudArmorRulePriority('publicEndpoints', firstOffset + i);
    const {
      hostname,
      hostPrefixRegex,
      pathPrefix,
      restrictToRateLimitedPaths,
      throttleAcrossAllEndpointsPerIp,
    } = singleServiceThrottle;
    const throttled = throttleAcrossAllEndpointsPerIp !== undefined;
    // leave out the rule but consume the priority number if max is 0
    // this makes the pulumi update cleaner if toggling just one service
    const skipRule = throttled && throttleAcrossAllEndpointsPerIp.maxRequestsBeforeHttp429 === 0;

    if (!skipRule) {
      const pathExpr = allowedPathsCondition(
        confEntryHead,
        scanExternalRateLimits,
        pathPrefix,
        restrictToRateLimitedPaths
      );
      const hostExpr = hostCondition(
        confEntryHead,
        CLUSTER_HOSTNAME,
        hostname
          ? { hostname }
          : hostPrefixRegex
            ? { hostPrefixRegex, perNodeHost: true }
            : undefined
      );
      const matchExpr = matchExpression(confEntryHead, pathExpr, hostExpr);

      new PolicyRule(
        ruleName,
        {
          securityPolicy: securityPolicy.name,
          region: securityPolicy.region,
          description: throttled
            ? `Per source IP throttle rule for all ${confEntryHead} API endpoints`
            : `Allow rule for all ${confEntryHead} API endpoints`,
          priority,
          preview: preview || singleServiceThrottle.rulePreviewOnly,
          action: throttled ? 'throttle' : 'allow',
          match: {
            expr: {
              expression: matchExpr,
            },
          },
          ...(throttled
            ? {
                rateLimitOptions: {
                  enforceOnKey: 'IP',
                  rateLimitThreshold: {
                    count: throttleAcrossAllEndpointsPerIp.maxRequestsBeforeHttp429,
                    intervalSec: throttleAcrossAllEndpointsPerIp.withinIntervalSeconds,
                  },
                  conformAction: 'allow',
                  exceedAction: 'deny(429)', // 429 Too Many Requests
                },
              }
            : {}),
        },
        opts
      );
    }
  });
}

/**
 * Adds a default deny rule to a security policy, plus - when all rules are in preview
 * mode - a preview-only deny-all rule just before it.
 */
function addDefaultDenyRule(
  securityPolicy: CloudArmorPolicy,
  preview: boolean,
  opts: pulumi.ResourceOptions
): void {
  if (preview) {
    // The default rule cannot be in preview mode, so in all-preview mode it has to
    // allow all traffic and therefore never reports anything as denied. This extra rule
    // sits just before it and records what an enforced setup would have blocked, so the
    // preview metrics reflect the reality of a non-preview deployment.
    new PolicyRule(
      'preview-deny-all',
      {
        securityPolicy: securityPolicy.name,
        region: securityPolicy.region,
        description: 'Preview-only deny all rule, mirroring the enforced default deny rule',
        priority: PREVIEW_DENY_RULE_NUMBER,
        preview: true,
        action: 'deny(403)',
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

  // The default rule is created together with the policy and cannot be added or
  // removed, only patched (the GCP provider turns a create at this priority into a
  // patch, and skips the delete). So we always declare it: dropping the resource when
  // `preview` is set would leave whatever action was last applied in place, i.e. a
  // previously enforced deny would silently keep denying.
  new PolicyRule(
    'default-deny',
    {
      securityPolicy: securityPolicy.name,
      region: securityPolicy.region,
      description: preview
        ? 'Default rule allowing all other traffic, as all rules are in preview mode'
        : 'Default rule to deny all other traffic',
      priority: DEFAULT_DENY_RULE_NUMBER,
      // default rule cannot be in preview mode; google API gives 400 if you try.
      // we assume that if you want all rules in preview, you *also* still want to
      // allow all traffic.
      preview: false,
      action: preview ? 'allow' : 'deny(403)',
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
