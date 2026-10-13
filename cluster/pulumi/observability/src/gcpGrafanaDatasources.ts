// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as gcp from '@pulumi/gcp';
import * as k8s from '@pulumi/kubernetes';
import * as yaml from 'js-yaml';
import {
  CLUSTER_BASENAME,
  GCP_PROJECT,
  GcpServiceAccount,
} from '@canton-network/splice-pulumi-common';
import { Input } from '@pulumi/pulumi';

export const grafanaGcpLoggingPlugin = 'googlecloud-logging-datasource';
const gcpMonitoringDatasource = { type: 'stackdriver', uid: 'gcp-monitoring' };
const gcpLoggingDatasource = { type: grafanaGcpLoggingPlugin, uid: 'gcp-logging' };

// Creates a GCP service account with read access to logs and metrics and provisions
// Grafana datasources (Cloud Monitoring and Cloud Logging) that authenticate with it.
export function createGrafanaGcpDatasources(namespace: Input<string>): k8s.core.v1.Secret {
  const serviceAccountName = `${CLUSTER_BASENAME}-grafana`;
  const serviceAccount = new GcpServiceAccount(serviceAccountName, {
    accountId: serviceAccountName,
    displayName: `Grafana logs & metrics viewer (${CLUSTER_BASENAME})`,
    description: 'Used by Grafana to read GCP logs and metrics (managed by Pulumi)',
    roles: ['roles/logging.viewer', 'roles/monitoring.viewer'],
  });

  const key = new gcp.serviceaccount.Key(`${serviceAccountName}-key`, {
    serviceAccountId: serviceAccount.name,
  });

  const datasources = key.privateKey.apply(encoded => {
    // the key is not available in mocked runs (e.g. when generating the expected files)
    const credentials = encoded
      ? JSON.parse(Buffer.from(encoded, 'base64').toString('utf-8'))
      : { client_email: 'unknown', private_key: 'unknown' };
    const jsonData = {
      authenticationType: 'jwt',
      clientEmail: credentials.client_email,
      defaultProject: GCP_PROJECT,
      tokenUri: credentials.token_uri ?? 'https://oauth2.googleapis.com/token',
    };
    const secureJsonData = { privateKey: credentials.private_key };
    return yaml.dump(
      {
        apiVersion: 1,
        datasources: [
          {
            name: 'Google Cloud Monitoring',
            ...gcpMonitoringDatasource,
            access: 'proxy',
            editable: false,
            jsonData,
            secureJsonData,
          },
          {
            name: 'Google Cloud Logging',
            ...gcpLoggingDatasource,
            access: 'proxy',
            editable: false,
            jsonData,
            secureJsonData,
          },
        ],
      },
      { noRefs: true }
    );
  });

  return new k8s.core.v1.Secret('grafana-gcp-datasources', {
    metadata: {
      namespace: namespace,
      name: 'grafana-gcp-datasources',
      labels: {
        grafana_datasource: '1',
      },
    },
    stringData: {
      'gcp-datasources.yaml': datasources,
    },
  });
}
