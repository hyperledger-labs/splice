// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as gcp from '@pulumi/gcp';
import {
  CLOUD_ARMOR_POLICY_NAME,
  CLUSTER_BASENAME,
  LB_REQUEST_LOG_RESOURCE_TYPES,
} from '@canton-network/splice-pulumi-common';

// Enforced rules are reported under enforcedSecurityPolicy, rules in preview mode under
// previewSecurityPolicy. A single request can be matched by both (e.g. a denying preview
// rule followed by an allowing enforced one), so each gets its own metric, counting only
// the requests its own side denies.
const ruleModes = {
  enforced: 'enforcedSecurityPolicy',
  previewed: 'previewSecurityPolicy',
} as const;
type RuleMode = keyof typeof ruleModes;

function rejectionsMetricName(mode: RuleMode): string {
  return `cloud_armor_${mode}_rejections_${CLUSTER_BASENAME}`;
}

function deniedByPolicyLogFilter(mode: RuleMode): string {
  const field = ruleModes[mode];
  return `jsonPayload.${field}.name="${CLOUD_ARMOR_POLICY_NAME}" AND jsonPayload.${field}.outcome="DENY"`;
}

/**
 * Log based metrics counting the requests rejected (or that would be rejected, for rules
 * in preview mode) by the Cloud Armor policy, labelled with the priority of the rule that
 * matched, so that dashboards and alerts can tell apart the rule groups by their priority range.
 *
 * Requires the backend request logging of the load balancer to be enabled
 */
export function installCloudArmorRejectionsMetrics(): void {
  (Object.keys(ruleModes) as RuleMode[]).forEach(mode => {
    const field = ruleModes[mode];
    new gcp.logging.Metric(`cloud_armor_${mode}_rejections`, {
      name: rejectionsMetricName(mode),
      description: `Requests ${mode === 'enforced' ? 'rejected' : 'that would be rejected'} by a ${mode} Cloud Armor rule`,
      filter: `resource.type=(${LB_REQUEST_LOG_RESOURCE_TYPES.map(t => `"${t}"`).join(' OR ')})
${deniedByPolicyLogFilter(mode)}
`,
      labelExtractors: {
        rule_priority: `EXTRACT(jsonPayload.${field}.priority)`,
      },
      metricDescriptor: {
        labels: [
          {
            description: `Priority of the ${mode} Cloud Armor rule that matched`,
            key: 'rule_priority',
          },
        ],
        metricKind: 'DELTA',
        valueType: 'INT64',
      },
    });
  });
}
