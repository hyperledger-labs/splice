// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as k8s from '@pulumi/kubernetes';
import { ExactNamespace } from '@lfdecentralizedtrust/splice-pulumi-common';

import { clusterIsResetPeriodically, enableAlerts } from './alertings';
import { monitoringConfig } from './config';
import {
  getNotificationChannel,
  installCloudSQLMaintenanceUpdateAlerts,
  installCloudSqlTxIdUtilizationAlert,
  installClusterMaintenanceUpdateAlerts,
  installGcpLoggingAlerts,
  installGcpQuotaAlerts,
  installLoggedSecretsAlerts,
} from './gcpAlerts';
import { istioMonitoring } from './istio';
import { configureObservability } from './observability';

const namespaceName = 'observability';

const namespace: ExactNamespace = {
  ns: new k8s.core.v1.Namespace(
    namespaceName,
    {
      metadata: {
        name: namespaceName,
        // istio really doesn't play well with prometheus
        // it seems to  modify the scraping calls from prometheus and change labels/include extra time series that make no sense
        labels: { 'istio-injection': 'disabled' },
      },
    },
    {
      aliases: [
        { name: 'observabilty' }, // Legacy typo
      ],
    }
  ),
  logicalName: namespaceName,
};
const observability = configureObservability(namespace);
istioMonitoring(namespace, [observability]);
if (enableAlerts && !clusterIsResetPeriodically) {
  const notificationChannel = getNotificationChannel();
  if (notificationChannel) {
    installGcpLoggingAlerts(notificationChannel);
    installClusterMaintenanceUpdateAlerts(notificationChannel);
    if (monitoringConfig.alerting.alerts.cloudSql.maintenance) {
      installCloudSQLMaintenanceUpdateAlerts(notificationChannel);
    }
    if (monitoringConfig.alerting.loggedSecretsFilter) {
      installLoggedSecretsAlerts(notificationChannel);
    }
    installGcpQuotaAlerts(notificationChannel, monitoringConfig.alerting.alerts.gcpQuotas);
    installCloudSqlTxIdUtilizationAlert(notificationChannel);
  }
}
