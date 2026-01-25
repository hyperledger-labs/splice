// Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as k8s from '@pulumi/kubernetes';
import {
  CLUSTER_BASENAME,
  clusterProdLike,
  config,
} from '@lfdecentralizedtrust/splice-pulumi-common';

import { namespace } from '../namespace';
import { flux } from './flux';

if (clusterProdLike) {
  const slackToken = new k8s.core.v1.Secret('slack', {
    metadata: {
      name: 'slack',
      namespace: namespace.ns.metadata.name,
    },
    type: 'Opaque',
    stringData: {
      token: config.requireEnv('SLACK_ACCESS_TOKEN'),
    },
  });

  const receiver = new k8s.apiextensions.CustomResource(
    'slack-notification-provider',
    {
      apiVersion: 'notification.toolkit.fluxcd.io/v1beta3',
      kind: 'Provider',
      metadata: {
        name: 'flux-slack-provider',
        namespace: namespace.ns.metadata.name,
      },
      spec: {
        type: 'slack',
        channel: config.requireEnv('SLACK_ALERT_NOTIFICATION_CHANNEL_FULL_NAME'),
        address: 'https://slack.com/api/chat.postMessage',
        secretRef: { name: slackToken.metadata.name },
      },
    },
    { dependsOn: [flux] }
  );

  new k8s.apiextensions.CustomResource(
    'deployment-alerts',
    {
      apiVersion: 'notification.toolkit.fluxcd.io/v1beta3',
      kind: 'Alert',
      metadata: {
        name: 'flux-deployment-alert',
        namespace: namespace.ns.metadata.name,
      },
      spec: {
        providerRef: { name: receiver.metadata.name },
        summary: 'Deployment for stack',
        eventMetadata: {
          cluster: CLUSTER_BASENAME,
        },
        eventSeverity: 'info',
        eventSources: [
          {
            kind: 'GitRepository',
            name: '*',
            matchLabels: {
              notifications: 'true',
            },
          },
        ],
      },
    },
    { dependsOn: [flux] }
  );
}
