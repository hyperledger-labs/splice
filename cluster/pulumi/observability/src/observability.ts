// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as k8s from '@pulumi/kubernetes';
import * as pulumi from '@pulumi/pulumi';
import * as grafana from '@pulumiverse/grafana';
import * as fs from 'fs';
import * as yaml from 'js-yaml';
import {
  CLOUD_ARMOR_POLICY_NAME,
  cloudArmorRulePriorityRegex,
  CLUSTER_BASENAME,
  CLUSTER_HOSTNAME,
  CLUSTER_NAME,
  clusterProdLike,
  commandScriptPath,
  DecentralizedSynchronizerUpgradeConfig,
  ExactNamespace,
  GCP_PROJECT,
  GrafanaKeys,
  HELM_MAX_HISTORY_SIZE,
  infraKubernetesScheduling,
  infraPremiumStorageClassName,
  infraStandardStorageClassName,
  loadTesterConfig,
  ObservabilityReleaseName,
  SPLICE_ROOT,
} from '@canton-network/splice-pulumi-common';
import { allSvsConfiguration } from '@canton-network/splice-pulumi-common-sv/src/singleSvConfig';
import {
  extraSvConfigsBasic,
  standardSvConfigsBasic,
} from '@canton-network/splice-pulumi-common-sv/src/svConfigsBasic';
import { SweepConfig } from '@canton-network/splice-pulumi-common-validator';
import { installSplicePostgres, Postgres } from '@canton-network/splice-pulumi-common/src/postgres';
import { StackReferences } from '@canton-network/splice-pulumi-common/src/stackReferences';
import { local } from '@pulumi/command';
import { getSecretVersionOutput } from '@pulumi/gcp/secretmanager/getSecretVersion';
import { Input } from '@pulumi/pulumi';

import {
  clusterIsResetPeriodically,
  enableAlertEmailToSupportTeam,
  enableAlerts,
  enableMiningRoundAlert,
  enablePrometheusAlerts,
  grafanaSmtpHost,
  slackAlertNotificationChannel,
  slackHighPrioAlertNotificationChannel,
  slackToken,
  supportTeamEmail,
} from './alertings';
import {
  type CloudArmorAlertConfig,
  cloudArmorConfig,
  monitoringConfig,
  prometheusConfig,
} from './config';
import { createGrafanaGcpDatasources, grafanaGcpLoggingPlugin } from './gcpGrafanaDatasources';
import { createGrafanaDashboards } from './grafana-dashboards';

function istioVirtualService(
  ns: k8s.core.v1.Namespace,
  name: string,
  serviceName: string,
  servicePort: number
) {
  const host = pulumi.interpolate`${serviceName}.${ns.metadata.name}.svc.cluster.local`;
  new k8s.apiextensions.CustomResource(
    `${name}-destination-rule`,
    {
      apiVersion: 'networking.istio.io/v1beta1',
      kind: 'DestinationRule',
      metadata: {
        name: name,
        namespace: ns.metadata.name,
      },
      spec: {
        host: host,
        trafficPolicy: {
          connectionPool: {
            http: {
              idleTimeout: '30s',
            },
            tcp: {
              tcpKeepalive: {
                time: '30s',
                interval: '10s',
                probes: 3,
              },
            },
          },
        },
      },
    },
    { deleteBeforeReplace: true }
  );
  new k8s.apiextensions.CustomResource(
    `${name}-virtual-service`,
    {
      apiVersion: 'networking.istio.io/v1alpha3',
      kind: 'VirtualService',
      metadata: {
        name: name,
        namespace: ns.metadata.name,
      },
      spec: {
        hosts: [`${name}.${CLUSTER_HOSTNAME}`],
        gateways: ['cluster-ingress/cn-http-gateway'],
        http: [
          {
            match: [{ port: 443 }, { port: 80 }],
            route: [
              {
                destination: {
                  host: host,
                  port: {
                    number: servicePort,
                  },
                },
              },
            ],
            // Istio's automatic retries are globally disabled (see defaultHttpRetryPolicy in
            // infra/src/istio.ts) because our app clients retry themselves. The observability
            // UIs are browser-facing, so there is no client-side retry and a transient upstream
            // connection drop shows up as "upstream connect error or disconnect/reset before
            // headers. reset reason: connection termination". We only retry failures that happen
            // before the request was handed to the upstream, so this stays safe for non-idempotent
            // requests.
            retries: {
              attempts: 3,
              perTryTimeout: '30s',
              retryOn: 'connect-failure,refused-stream,reset-before-request',
            },
          },
        ],
      },
    },
    { deleteBeforeReplace: true }
  );
}

const grafanaExternalUrl = `https://grafana.${CLUSTER_HOSTNAME}`;
const alertManagerExternalUrl = `https://alertmanager.${CLUSTER_HOSTNAME}`;
const prometheusExternalUrl = `https://prometheus.${CLUSTER_HOSTNAME}`;
const shouldIgnoreNoDataOrDataSourceError = clusterIsResetPeriodically;

// eslint-disable-next-line @typescript-eslint/no-explicit-any
const istioDashboardVersions: pulumi.Output<any> =
  StackReferences.infra.requireOutput('istioDashboardVersions');

export function configureObservability(namespace: ExactNamespace): pulumi.Resource {
  // If the stack version is updated the crd version might need to be upgraded as well, check the release notes https://artifacthub.io/packages/helm/prometheus-community/kube-prometheus-stack
  const stackVersion = '91.4.1';
  const prometheusStackCrdVersion = '0.94.0';
  const namespaceName = namespace.logicalName;
  const postgres = installPostgres(namespace);
  const adminPassword = grafanaKeysFromSecret().adminPassword;
  const prometheusStack = new k8s.helm.v3.Release(
    'observability-metrics',
    {
      name: ObservabilityReleaseName,
      chart: 'kube-prometheus-stack',
      version: stackVersion,
      namespace: namespaceName,
      repositoryOpts: {
        repo: 'https://prometheus-community.github.io/helm-charts',
      },
      values: {
        fullnameOverride: 'prometheus',
        commonLabels: {
          'digitalasset.com/scope': 'ci',
          'digitalasset.com/component': 'prometheus-stack',
        },
        defaultRules: {
          // enable recording rules for all the k8s metrics
          create: true,
          disabled: {
            // The timeout is not configurable, and we have currently jobs that are expected to run for more than
            // the 12 hr timeout, so we disable the alert. There is an alert if the job fails, so the only risk is
            // a job that never completes.
            KubeJobNotCompleted: true,
          },
        },
        kubeControllerManager: {
          enabled: false,
        },
        kubeEtcd: {
          enabled: false,
        },
        kubeScheduler: {
          enabled: false,
        },
        kubeDns: {
          enabled: true,
        },
        kubeProxy: {
          enabled: false,
        },
        alertmanager: {
          enabled: true,
          config: {
            route: {
              receiver: enableAlerts && enablePrometheusAlerts ? 'slack' : 'null',
              group_by: ['namespace'],
              continue: false,
              routes: [
                {
                  receiver: 'null',
                  matchers: ['alertname=~"Watchdog|InfoInhibitor"'],
                  continue: false,
                },
              ],
            },
            receivers: [
              {
                name: 'null',
              },
              ...(enableAlerts && enablePrometheusAlerts && slackAlertNotificationChannel
                ? [
                    {
                      name: 'slack',
                      slack_configs: [
                        {
                          api_url: 'https://slack.com/api/chat.postMessage',
                          channel: slackAlertNotificationChannel,
                          send_resolved: true,
                          http_config: {
                            authorization: {
                              credentials: slackToken(),
                            },
                          },
                          title: '{{ template "slack_title" . }}',
                          text: '{{ template "slack_message" . }}',
                        },
                      ],
                    },
                  ]
                : []),
            ],
          },
          alertmanagerSpec: {
            externalUrl: alertManagerExternalUrl,
            logFormat: 'json',
            storage: {
              volumeClaimTemplate: {
                metadata: { name: 'alertmanager-hd-pvc' },
                spec: {
                  storageClassName: infraStandardStorageClassName,
                  accessModes: ['ReadWriteOnce'],
                  resources: {
                    requests: {
                      storage: '5Gi',
                    },
                  },
                },
              },
            },
            ...infraKubernetesScheduling,
          },
          templateFiles: {
            'template.tmpl': substituteSlackNotificationTemplate(
              readAlertingManagerFile('slack-notification.tmpl')
            ),
          },
        },
        coreDns: {
          enabled: false,
        },
        prometheusOperator: {
          logFormat: 'json',
          admissionWebhooks: {
            enabled: false,
          },
          tls: {
            enabled: false, // because `admissionWebhooks` are disabled, see: https://github.com/prometheus-community/helm-charts/issues/418
          },
          ...infraKubernetesScheduling,
        },
        prometheus: {
          prometheusSpec: {
            // discover all pod/service monitors across all namespaces
            podMonitorSelector: {
              matchLabels: null,
            },
            serviceMonitorSelector: {
              matchLabels: null,
            },
            enableFeatures: ['memory-snapshot-on-shutdown', 'promql-experimental-functions'],
            enableRemoteWriteReceiver: true,
            retention: prometheusConfig.retentionDuration,
            retentionSize: prometheusConfig.retentionSize,
            resources: {
              requests: {
                memory: clusterProdLike ? (!clusterIsResetPeriodically ? '8Gi' : '3Gi') : '2Gi',
                cpu: clusterProdLike ? (!clusterIsResetPeriodically ? '2' : '1') : '0.5',
              },
            },
            logFormat: 'json',
            remoteWriteDashboards: true,
            // fix for https://github.com/prometheus/prometheus/issues/6857
            additionalArgs: [{ name: 'storage.tsdb.max-block-duration', value: '1d' }],
            scrapeNativeHistograms: true,
            storageSpec: {
              volumeClaimTemplate: {
                metadata: { name: 'prometheus-hd-pvc' },
                spec: {
                  storageClassName: infraPremiumStorageClassName,
                  accessModes: ['ReadWriteOnce'],
                  resources: {
                    requests: {
                      storage: prometheusConfig.storageSize,
                    },
                  },
                },
              },
            },
            externalUrl: prometheusExternalUrl,
            ...infraKubernetesScheduling,
          },
        },
        grafana: {
          fullnameOverride: 'grafana',
          envFromSecret: postgres.secretName,
          plugins: [grafanaGcpLoggingPlugin],
          ingress: {
            enabled: false,
          },
          dashboardProviders: {
            'dashboardproviders.yaml': {
              apiVersion: 1,
              providers: [
                {
                  name: 'istio',
                  orgId: 1,
                  folder: 'Istio',
                  type: 'file',
                  disableDeletion: false,
                  editable: true,
                  options: {
                    path: '/var/lib/grafana/dashboards/istio',
                  },
                },
                {
                  name: 'gid-testing',
                  orgId: 1,
                  folder: 'testing',
                  type: 'file',
                  disableDeletion: false,
                  editable: true,
                  options: {
                    path: '/var/lib/grafana/dashboards/k6s',
                  },
                },
              ],
            },
          },
          dashboards: {
            k6s: {
              native_prometheus: {
                gnetId: 18030,
                datasource: 'Prometheus',
                revision: 8,
              },
            },
            istio: {
              control_plane: {
                gnetId: 7645,
                datasource: 'Prometheus',
                revision: istioDashboardVersions.apply(v => v.general),
              },
              mesh: {
                gnetId: 7639,
                datasource: 'Prometheus',
                revision: istioDashboardVersions.apply(v => v.general),
              },
              performance: {
                gnetId: 11829,
                datasource: 'Prometheus',
                revision: istioDashboardVersions.apply(v => v.general),
              },
              service: {
                gnetId: 7636,
                datasource: 'Prometheus',
                revision: istioDashboardVersions.apply(v => v.general),
              },
              workload: {
                gnetId: 7630,
                datasource: 'Prometheus',
                revision: istioDashboardVersions.apply(v => v.general),
              },
              wasm: {
                gnetId: 13277,
                datasource: 'Prometheus',
                revision: istioDashboardVersions.apply(v => v.wasm),
              },
            },
          },
          sidecar: {
            dashboards: {
              enabled: true,
              folderAnnotation: 'folder',
              provider: { foldersFromFilesStructure: true, allowUiUpdates: true },
            },
            alerts: {
              enabled: true,
            },
          },
          'grafana.ini': {
            server: {
              root_url: grafanaExternalUrl,
            },
            date_formats: {
              default_timezone: 'UTC',
            },
            feature_toggles: {
              addFieldFromCalculationStatFunctions: true,
            },
            smtp: enableAlertEmailToSupportTeam
              ? {
                  enabled: true,
                  host: grafanaSmtpHost,
                  from_address: 'noreply@digitalasset.com',
                  from_name: 'Canton Network Alerts',
                  skip_verify: true,
                }
              : undefined,
            database: {
              type: 'postgres',
              host: pulumi.interpolate`${postgres.address}:5432`,
              user: 'cnadmin',
              password: '${postgresPassword}', // replaced from the secret
              name: 'cantonnet',
            },
          },
          deploymentStrategy: {
            // required for the pvc
            type: 'Recreate',
          },
          persistence: {
            enabled: true,
            type: 'pvc',
            accessModes: ['ReadWriteOnce'],
            size: '5Gi',
            storageClassName: infraStandardStorageClassName,
          },
          adminUser: 'cn-admin',
          adminPassword: adminPassword,
          ...infraKubernetesScheduling,
        },
        'kube-state-metrics': {
          fullnameOverride: 'ksm',
          customResourceState: {
            enabled: true,
            config: {
              spec: {
                resources: [
                  // flux config from https://github.com/fluxcd/flux2-monitoring-example/blob/main/monitoring/controllers/kube-prometheus-stack/kube-state-metrics-config.yaml
                  {
                    groupVersionKind: {
                      group: 'source.toolkit.fluxcd.io',
                      version: 'v1',
                      kind: 'GitRepository',
                    },
                    metricNamePrefix: 'splice_deployment_flux',
                    metrics: [
                      {
                        name: 'resource_info',
                        help: 'The current state of a Flux GitRepository resource.',
                        each: {
                          type: 'Gauge',
                          gauge: {
                            labelsFromPath: {
                              name: ['metadata', 'name'],
                            },
                          },
                        },
                        labelsFromPath: {
                          exported_namespace: ['metadata', 'namespace'],
                          ready: ['status', 'conditions', '[type=Ready]', 'status'],
                          suspended: ['spec', 'suspend'],
                          revision: ['status', 'artifact', 'revision'],
                          url: ['spec', 'url'],
                        },
                      },
                    ],
                  },
                  // pulumi resources
                  {
                    groupVersionKind: {
                      group: 'pulumi.com',
                      version: 'v1',
                      kind: 'Stack',
                    },
                    metricNamePrefix: 'splice_deployment_pulumi',
                    labelsFromPath: {
                      stack: ['spec', 'stack'],
                      state: ['status', 'lastUpdate', 'state'],
                      // condition_type: ['status', 'conditions', '[status=True]', 'type'],
                      // condition_reason: ['status', 'conditions', '[status=True]', 'reason'],
                      generation: ['status', 'observedGeneration'],
                    },
                    metrics: [
                      // from https://github.com/kubernetes/kube-state-metrics/blob/main/docs/metrics/extend/customresourcestate-metrics.md#example-for-status-conditions-on-kubernetes-controllers
                      {
                        name: 'stack_condition',
                        help: 'The current conditions of a Pulumi Stack resource.',
                        each: {
                          type: 'Gauge',
                          gauge: {
                            path: ['status', 'conditions'],
                            labelsFromPath: {
                              type: ['type'],
                              reason: ['reason'],
                            },
                            valueFrom: ['status'],
                          },
                        },
                      },
                      {
                        name: 'stack_status',
                        help: 'The current state of a Pulumi Stack resource.',
                        each: {
                          type: 'Gauge',
                          gauge: {
                            path: ['status'],
                            labelsFromPath: {
                              state: ['lastUpdate', 'state'],
                            },
                            valueFrom: ['observedGeneration'],
                          },
                        },
                      },
                    ],
                  },
                ],
              },
            },
          },
          rbac: {
            extraRules: [
              {
                apiGroups: ['source.toolkit.fluxcd.io', 'notification.toolkit.fluxcd.io'],
                resources: ['gitrepositories', 'alerts', 'providers', 'receivers'],
                verbs: ['list', 'watch'],
              },
              {
                apiGroups: ['pulumi.com'],
                resources: ['stacks'],
                verbs: ['list', 'watch'],
              },
            ],
          },
          ...infraKubernetesScheduling,
        },
        'prometheus-node-exporter': {
          fullnameOverride: 'node-exporter',
        },
        database: {},
      },
      maxHistory: HELM_MAX_HISTORY_SIZE,
    },
    {
      dependsOn: [namespace.ns],
    }
  );

  const path = commandScriptPath('cluster/pulumi/observability/prometheus-crd-update.sh');
  new local.Command(
    `update-prometheus-crd-${prometheusStackCrdVersion}`,
    {
      create: `bash ${path} ${prometheusStackCrdVersion}`,
    },
    { dependsOn: prometheusStack }
  );

  istioVirtualService(namespace.ns, 'prometheus', 'prometheus-prometheus', 9090);
  istioVirtualService(namespace.ns, 'grafana', 'grafana', 80);
  istioVirtualService(namespace.ns, 'alertmanager', 'prometheus-alertmanager', 9093);
  // In the observability cluster, we install a version of the dashboards with a filter
  // that prevents running expensive queries when the dashboard just loads
  createGrafanaDashboards(namespaceName);
  createGrafanaGcpDatasources(namespaceName);
  // enable the slack alerts only for "prod" clusters
  const slackAccessToken = enableAlerts ? slackToken() : 'None';
  const slackNotificationChannel = (enableAlerts && slackAlertNotificationChannel) || 'None';
  const slackHighPrioNotificationChannel =
    enableAlerts && slackHighPrioAlertNotificationChannel
      ? slackHighPrioAlertNotificationChannel
      : 'None';
  const supportTeamEmailAddress =
    enableAlerts && enableAlertEmailToSupportTeam && supportTeamEmail ? supportTeamEmail : 'None';

  grafanaContactPoints(
    namespaceName,
    slackAccessToken,
    slackNotificationChannel,
    slackHighPrioNotificationChannel,
    supportTeamEmailAddress
  );
  createGrafanaAlerting(namespaceName);
  if (monitoringConfig.enableGrafanaServiceAccountToken) {
    createGrafanaServiceAccount(namespaceName, adminPassword, [prometheusStack, postgres.database]);
  }
  createGrafanaEnvoyFilter(namespaceName, [prometheusStack]);

  if (prometheusConfig.installPrometheusPushgateway) {
    new k8s.helm.v3.Release('prometheus-pushgateway', {
      name: 'prometheus-pushgateway',
      chart: 'prometheus-pushgateway',
      version: '3.4.1',
      namespace: namespaceName,
      repositoryOpts: {
        repo: 'https://prometheus-community.github.io/helm-charts',
      },
      values: {
        serviceMonitor: {
          enabled: true,
          namespace: namespaceName,
          additionalLabels: { release: 'prometheus-grafana-monitoring' },
        },
        ...infraKubernetesScheduling,
      },
      maxHistory: HELM_MAX_HISTORY_SIZE,
    });
  }

  return prometheusStack;
}

// Even though the AuthorizationPolicy explicitly allows all traffic to Grafana api
// to not go through istio authentication, the RequestAuthentication still rejects
// requests with an Authorization header that is not a jwt!
// We work around that by putting the authorization for Grafana in a custom header,
// x-non-jwt-auth, and using an EnvoyFilter to copy that header to the Authorization header
// before it hits the pod.
function createGrafanaEnvoyFilter(namespace: Input<string>, dependsOn: pulumi.Resource[]) {
  new k8s.apiextensions.CustomResource(
    'grafana-envoy-filter',
    {
      apiVersion: 'networking.istio.io/v1alpha3',
      kind: 'EnvoyFilter',
      metadata: {
        name: 'grafana-authorization-header-filter',
        namespace: namespace,
      },
      spec: {
        workloadSelector: {
          labels: {
            'app.kubernetes.io/name': 'grafana',
          },
        },
        configPatches: [
          {
            applyTo: 'HTTP_FILTER',
            match: {
              context: 'SIDECAR_INBOUND',
              listener: {
                filterChain: {
                  filter: {
                    name: 'envoy.filters.network.http_connection_manager',
                    subFilter: {
                      name: 'envoy.filters.http.router',
                    },
                  },
                },
              },
            },
            patch: {
              operation: 'INSERT_BEFORE',
              value: {
                name: 'envoy.lua',
                typed_config: {
                  '@type': 'type.googleapis.com/envoy.extensions.filters.http.lua.v3.Lua',
                  inlineCode:
                    'function envoy_on_request(request_handle)\n' +
                    '  headers = request_handle: headers()\n' +
                    '  request_handle: headers(): add("Authorization", headers: get("x-non-jwt-auth"))\n' +
                    'end',
                },
              },
            },
          },
        ],
      },
    },
    {
      dependsOn: dependsOn,
    }
  );
}

function createGrafanaServiceAccount(
  namespace: Input<string>,
  adminPassword: pulumi.Output<string>,
  dependsOn: pulumi.Resource[]
) {
  const grafanaProvider = new grafana.Provider('grafana', {
    auth: adminPassword.apply(pwd => `cn-admin:${pwd}`),
    url: grafanaExternalUrl,
  });

  const serviceAccountResource = new grafana.oss.ServiceAccount(
    'grafanaSA',
    {
      role: 'Editor',
    },
    {
      provider: grafanaProvider,
      dependsOn: [...dependsOn, grafanaProvider],
    }
  );
  const serviceAccountToken = new grafana.oss.ServiceAccountToken(
    'grafanaSAToken',
    {
      serviceAccountId: serviceAccountResource.id,
      name: 'grafana-sa-token',
    },
    {
      provider: grafanaProvider,
      dependsOn: dependsOn.concat([grafanaProvider, serviceAccountResource]),
    }
  );
  new k8s.core.v1.Secret('grafana-service-account-token-secret', {
    metadata: {
      namespace: namespace,
      name: 'grafana-service-account-token-secret',
    },
    stringData: serviceAccountToken.key.apply(key => ({ token: key })),
  });
}

function grafanaContactPoints(
  namespace: Input<string>,
  slackToken: string,
  slackAlertNotificationChannel: string,
  slackHighPrioAlertNotificationChannel: string,
  supportTeamEmail: string
) {
  new k8s.core.v1.Secret(
    'slack-alert-notification-channel',
    {
      metadata: {
        namespace: namespace,
        labels: {
          grafana_alert: '',
        },
      },
      data: {
        'contactPoints.yaml': Buffer.from(
          readGrafanaAlertingFile('contact_points.yaml')
            .replaceAll('$SLACK_ACCESS_TOKEN', slackToken)
            .replaceAll('$SLACK_NOTIFICATION_CHANNEL', slackAlertNotificationChannel)
            .replaceAll(
              '$SLACK_HIGH_PRIO_NOTIFICATION_CHANNEL',
              slackHighPrioAlertNotificationChannel
            )
            .replaceAll('$SUPPORT_TEAM_EMAIL', supportTeamEmail)
        ).toString('base64'),
      },
    },
    {
      // the sidecar reacts to k8s events, so if it deletes it afterward, as it has the same name it will just delete the file
      deleteBeforeReplace: true,
    }
  );
}

function substituteSlackNotificationTemplate(file: string) {
  return file
    .replaceAll('$CLUSTER_BASENAME', CLUSTER_BASENAME)
    .replaceAll('$CLUSTER_NAME', CLUSTER_NAME)
    .replaceAll('$GCP_PROJECT', GCP_PROJECT)
    .replaceAll('$GRAFANA_EXTERNAL_URL', grafanaExternalUrl);
}

function defaultAlertSubstitutions(alert: string): string {
  return alert.replaceAll(
    '$NODATA',
    monitoringConfig.alerting.enableNoDataAlerts ? 'Alerting' : 'OK'
  );
}

function substituteScanConnectionDisagreementAlerts(alert: string): string {
  const config = monitoringConfig.alerting.alerts.scanConnectionDisagreement;
  const matchers: string[] = [];
  if (config.excludedRequests.length > 0) {
    matchers.push(`request!~"${config.excludedRequests.join('|')}"`);
  }
  if (config.excludedConnections.length > 0) {
    matchers.push(`scan_connection!~"${config.excludedConnections.join('|')}"`);
  }
  const bareFilter = matchers.join(', ');
  const filter = bareFilter ? `, ${bareFilter}` : '';
  const connectionMatchers: string[] = [];
  if (config.excludedConnections.length > 0) {
    connectionMatchers.push(`scan_connection!~"${config.excludedConnections.join('|')}"`);
  }
  if (config.excludedHttpStatusCodes.length > 0) {
    matchers.push(`http_status!~"${config.excludedHttpStatusCodes.join('|')}"`);
    connectionMatchers.push(`http_status!~"${config.excludedHttpStatusCodes.join('|')}"`);
  }
  const connectionBareFilter = connectionMatchers.join(', ');
  const connectionFilter = connectionBareFilter ? `, ${connectionBareFilter}` : '';
  return alert
    .replaceAll('$SCAN_DISAGREEMENT_FILTER_BARE', bareFilter)
    .replaceAll('$SCAN_DISAGREEMENT_CONNECTION_FILTER_BARE', connectionBareFilter)
    .replaceAll('$SCAN_DISAGREEMENT_CONNECTION_FILTER', connectionFilter)
    .replaceAll('$SCAN_DISAGREEMENT_FILTER', filter)
    .replaceAll(
      '$SCAN_DISAGREEMENT_SUCCESS_THRESHOLD_PERCENT',
      (config.alertThreshold * 100).toString()
    )
    .replaceAll('$SCAN_DISAGREEMENT_SUCCESS_THRESHOLD', config.alertThreshold.toString());
}

function substituteDsoMissedConfirmationsAlerts(alert: string): string {
  const config = monitoringConfig.alerting.alerts.dsoMissedConfirmations;
  return alert
    .replaceAll('$DSO_MISSED_CONFIRMATIONS_THRESHOLD', config.threshold.toString())
    .replaceAll('$DSO_MISSED_CONFIRMATIONS_WINDOW_SECONDS', (config.windowMinutes * 60).toString())
    .replaceAll('$DSO_MISSED_CONFIRMATIONS_WINDOW_MINUTES', config.windowMinutes.toString());
}

function substituteSpliceRateLimitsAlerts(alert: string): string {
  const config = monitoringConfig.alerting.alerts.spliceRateLimits;
  const bareFilter =
    config.excludedLimiters.length > 0 ? `limiter!~"${config.excludedLimiters.join('|')}"` : '';
  const filter = bareFilter ? `, ${bareFilter}` : '';
  return alert
    .replaceAll('$SPLICE_RATE_LIMITS_USAGE_THRESHOLD', config.usageThreshold.toString())
    .replaceAll(
      '$SPLICE_RATE_LIMITS_REJECTION_COUNT_THRESHOLD',
      config.rejectionCountThreshold.toString()
    )
    .replaceAll('$SPLICE_RATE_LIMITS_FILTER', filter);
}

function substituteCloudArmorAlerts(alerts: string): string {
  const { deniedRequests, wafRejections, throttleRejections } =
    monitoringConfig.alerting.alerts.cloudArmor;
  const hasPreviewOnlyRules =
    cloudArmorConfig.allRulesPreviewOnly ||
    (cloudArmorConfig.wafRules.enabled && cloudArmorConfig.wafRules.previewOnly);
  // Rules in preview mode do not actually deny anything, so we also alert on the requests
  // they would have denied. The rule specific alerts rely on the log based metrics, as the
  // Cloud Armor metrics only expose whether a request was blocked, not by which rule.
  // Each rule group has its own priority prefix, so the rule specific alerts just match
  // on it. The public endpoint rules only ever deny when throttling, so any rejection
  // with that prefix is a throttle rejection.
  const enabledRules: { [uid: string]: boolean } = {
    'cloud-armor-denied': true,
    'cloud-armor-previewed-denied': hasPreviewOnlyRules,
    'cloud-armor-waf-rejections':
      cloudArmorConfig.logging.enabled && cloudArmorConfig.wafRules.enabled,
    'cloud-armor-throttle-rejections': cloudArmorConfig.logging.enabled,
  };
  const windowSubstitutions = (name: string, config: CloudArmorAlertConfig) => (s: string) =>
    s
      .replaceAll(`$CLOUD_ARMOR_${name}_THRESHOLD`, config.threshold.toString())
      .replaceAll(`$CLOUD_ARMOR_${name}_WINDOW`, `${config.alignmentPeriodSeconds}s`)
      .replaceAll(`$CLOUD_ARMOR_${name}_FOR`, `${config.durationSeconds}s`)
      // leave some room for the ingestion delay of the GCP metrics
      .replaceAll(
        `$CLOUD_ARMOR_${name}_RANGE_SECONDS`,
        (config.alignmentPeriodSeconds + 600).toString()
      );
  const substituted = [
    windowSubstitutions('DENIED', deniedRequests),
    windowSubstitutions('WAF', wafRejections),
    windowSubstitutions('THROTTLE', throttleRejections),
  ].reduce(
    (s, substitute) => substitute(s),
    alerts
      .replaceAll('$CLOUD_ARMOR_WAF_RULE_PRIORITY_REGEX', cloudArmorRulePriorityRegex('waf'))
      .replaceAll(
        '$CLOUD_ARMOR_THROTTLE_RULE_PRIORITY_REGEX',
        cloudArmorRulePriorityRegex('publicEndpoints')
      )
      .replaceAll('$CLOUD_ARMOR_POLICY_NAME', CLOUD_ARMOR_POLICY_NAME)
      .replaceAll('$CLUSTER_BASENAME', CLUSTER_BASENAME)
      .replaceAll('$GCP_PROJECT', GCP_PROJECT)
  );
  const content = yaml.load(substituted) as GrafanaRuleFile;
  content.groups.forEach(group => {
    group.rules = group.rules.filter(rule => {
      if (!(rule.uid in enabledRules)) {
        throw new Error(`Unknown Cloud Armor alert rule ${rule.uid}`);
      }
      return enabledRules[rule.uid];
    });
  });
  return yaml.dump(content, { lineWidth: -1 });
}

// AmuletMetrics was previously using owner.toString instead of owner.toProtoPrimitive
// This function makes it compatible for both.
function partyIdTransform(partyId: string) {
  const parts = partyId.split('::');
  const hint = parts[0];
  const namespace = parts[1];
  return {
    regex: `${hint}::${namespace.substring(0, 8)}.*`,
    hint: hint,
  };
}

function createGrafanaAlerting(namespace: Input<string>) {
  const sweepConfigs: SweepConfig[] = extraSvConfigsBasic
    .concat(standardSvConfigsBasic)
    .map(sv => sv.sweep!)
    .filter(e => e != undefined);
  const cantonBftEnabled =
    DecentralizedSynchronizerUpgradeConfig.active.sequencer.enableBftSequencer;
  const cometbftPruningHighestBlockRetain = allSvsConfiguration
    .map(sv => sv.pruning?.cometbft?.retainBlocks)
    .filter((retainBlocks): retainBlocks is number => retainBlocks !== undefined)
    .sort((a, b) => a - b)
    .pop();
  new k8s.core.v1.ConfigMap(
    'grafana-alerting',
    {
      metadata: {
        namespace: namespace,
        labels: {
          grafana_alert: '',
        },
      },
      data: Object.fromEntries(
        Object.entries({
          ...(enableAlerts
            ? {
                'notification_policies.yaml': grafanaAlertNotificationPolicies(),
              }
            : {}),
          ...(enableAlerts && monitoringConfig.alerting.muteTimeIntervals.length > 0
            ? {
                'mute_time_intervals.yaml': yaml.dump({
                  apiVersion: 1,
                  muteTimes: monitoringConfig.alerting.muteTimeIntervals.map(interval => ({
                    orgId: 1,
                    name: interval.name,
                    time_intervals: interval.timeWindows.map(window => ({
                      times: window.times.map(t => ({
                        start_time: t.startTime,
                        end_time: t.endTime,
                      })),
                      ...(window.weekdays ? { weekdays: window.weekdays } : {}),
                      location: 'UTC',
                    })),
                  })),
                }),
              }
            : {}),
          ...{
            'acs-stores_alerts.yaml': readGrafanaAlertingFile('acs-stores_alerts.yaml').replaceAll(
              '$NODATA',
              loadTesterConfig?.enable ? 'Alerting' : 'OK'
            ),
            'pruning_alerts.yaml': readGrafanaAlertingFile('pruning_alerts.yaml')
              .replaceAll('$NODATA', loadTesterConfig?.enable ? 'Alerting' : 'OK')
              .replaceAll(
                '$PARTICIPANT_PRUNING_RETENTION_IN_DAYS',
                monitoringConfig.alerting.alerts.pruning.participantRetentionDays.toString()
              )
              .replaceAll(
                '$PARTICIPANT_PRUNING_RETENTION_IN_HOURS',
                (monitoringConfig.alerting.alerts.pruning.participantRetentionDays * 24).toString()
              )
              .replaceAll(
                '$SEQUENCER_PRUNING_RETENTION_IN_DAYS',
                monitoringConfig.alerting.alerts.pruning.sequencerRetentionDays.toString()
              )
              .replaceAll(
                '$SEQUENCER_PRUNING_RETENTION_IN_HOURS',
                (monitoringConfig.alerting.alerts.pruning.sequencerRetentionDays * 24).toString()
              )
              .replaceAll(
                '$MEDIATOR_PRUNING_RETENTION_IN_DAYS',
                monitoringConfig.alerting.alerts.pruning.mediatorRetentionDays.toString()
              )
              .replaceAll(
                '$MEDIATOR_PRUNING_RETENTION_IN_HOURS',
                (monitoringConfig.alerting.alerts.pruning.mediatorRetentionDays * 24).toString()
              ),
            'deployment_alerts.yaml': readGrafanaAlertingFile('deployment_alerts.yaml').replaceAll(
              '$PENDING_PERIOD_IN_MINUTES',
              monitoringConfig.alerting.alerts.deployment.pendingPeriodMinutes.toString()
            ),
            'load-tester_alerts.yaml': readGrafanaAlertingFile('load-tester_alerts.yaml')
              .replaceAll(
                '$LOAD_TESTER_MIN_RATE',
                loadTesterConfig?.minRate ? loadTesterConfig?.minRate.toString() : '1.0'
              )
              .replaceAll('$NODATA', loadTesterConfig?.enable ? 'Alerting' : 'OK'),
            ...(cantonBftEnabled
              ? {
                  'cometbft_deleted_alerts.yaml': readGrafanaAlertingFile('cometbft_deleted.yaml'),
                }
              : {
                  'cometbft_alerts.yaml': readGrafanaAlertingFile('cometbft_alerts.yaml')
                    .replaceAll(
                      '$EXPECTED_MAX_BLOCK_RATE_PER_SECOND',
                      monitoringConfig.alerting.alerts.cometbft.expectedMaxBlocksPerSecond.toString()
                    )
                    .replaceAll(
                      '$COMETBFT_PRUNING_DISABLED',
                      (cometbftPruningHighestBlockRetain === undefined).toString()
                    )
                    .replaceAll(
                      '$COMETBFT_RETAIN_BLOCKS',
                      String((cometbftPruningHighestBlockRetain || 0) * 1.05)
                    ),
                }),
            'automation_alerts.yaml': readGrafanaAlertingFile('automation_alerts.yaml')
              .replaceAll(
                '$CONTENTION_THRESHOLD_PERCENTAGE_PER_NAMESPACE',
                monitoringConfig.alerting.alerts.delegatelessContention.thresholdPerNamespace.toString()
              )
              .replaceAll(
                '$NON_LOCAL_CONTENTION_ERRORS_THRESHOLD_PERCENTAGE_PER_NAMESPACE',
                monitoringConfig.alerting.alerts.delegatelessNonLocalContention.thresholdPerNamespace.toString()
              )
              .replaceAll(
                '$INGESTION_ENTRIES_PER_BATCH_THRESHOLD',
                monitoringConfig.alerting.alerts.ingestion.thresholdEntriesPerBatch.toString()
              ),
            'acs_snapshots_alerts.yaml': readGrafanaAlertingFile('acs_snapshots_alerts.yaml')
              .replaceAll(
                '$ACS_SNAPSHOT_SAVE_LATENCY_THRESHOLD_SECONDS',
                monitoringConfig.alerting.alerts.acsSnapshots.saveLatencyThresholdSeconds.toString()
              )
              .replaceAll(
                '$ACS_SNAPSHOT_UPDATE_LATENCY_THRESHOLD_SECONDS',
                monitoringConfig.alerting.alerts.acsSnapshots.updateLatencyThresholdSeconds.toString()
              ),
            'sv-status-report_alerts.yaml': readAndSetAlertRulesGrafanaAlertingFile(
              'sv-status-report_alerts.yaml',
              [
                {
                  reportPublisherFormula: '=~"Digital-Asset-1|Digital-Asset-2|DA-Helm-Test-Node"',
                  notificationDelay: '5m',
                  teamLabel: 'canton-network',
                  subtitle: 'internal SVs 5m',
                  uid: 'adlmhpz5iv4sgc',
                  priority: monitoringConfig.alerting.enableExtraHighPrioAlerts ? 'high' : 'medium',
                },
                {
                  reportPublisherFormula: '=~"Digital-Asset-1|Digital-Asset-2|DA-Helm-Test-Node"',
                  notificationDelay: '15m',
                  teamLabel: 'support',
                  subtitle: 'internal SVs 15m',
                  uid: 'bdlmhpz5iv4sgc',
                },
                {
                  reportPublisherFormula: '!~"Digital-Asset-1|Digital-Asset-2|DA-Helm-Test-Node"',
                  notificationDelay: '15m',
                  teamLabel: 'da',
                  subtitle: 'external SVs 15m',
                  uid: 'cdlmhpz5iv4sgc',
                },
              ]
            ),
            ...(enableMiningRoundAlert
              ? {
                  'mining-rounds_alerts.yaml': readGrafanaAlertingFile('mining-rounds_alerts.yaml'),
                }
              : {}),
            'acknowledgement_alerts.yaml': readGrafanaAlertingFile(
              'acknowledgement_alerts.yaml'
            ).replaceAll(
              '$MEDIATOR_ACKNOWLEDGEMENT_LAG_SECONDS',
              monitoringConfig.alerting.alerts.mediators.acknowledgementLagSeconds.toString()
            ),
            'sequencer_client_delay_alerts.yaml': readGrafanaAlertingFile(
              'sequencer_client_delay_alerts.yaml'
            ).replaceAll(
              '$SEQUENCER_CLIENT_DELAY_THRESHOLD_SECONDS',
              monitoringConfig.alerting.alerts.sequencerClientDelay.seconds.toString()
            ),
            ...(monitoringConfig.alerting.alerts.acsCommitments.usePv36Metrics
              ? {
                  'acs_commitment_deleted_alerts.yaml': readGrafanaAlertingFile(
                    'acs_commitment_deleted.yaml'
                  ),
                  'acs_commitment_pv36_alerts.yaml': readGrafanaAlertingFile(
                    'acs_commitment_pv36_alerts.yaml'
                  )
                    .replaceAll(
                      '$ACS_COMMITMENT_CHECKPOINT_DELAY_THRESHOLD_SECONDS',
                      monitoringConfig.alerting.alerts.acsCommitments.checkpointDelay.seconds.toString()
                    )
                    .replaceAll(
                      '$ACS_COMMITMENT_DELAY_THRESHOLD_SECONDS',
                      monitoringConfig.alerting.alerts.acsCommitments.completedDelay.seconds.toString()
                    ),
                }
              : {
                  'acs_commitment_alerts.yaml': readGrafanaAlertingFile(
                    'acs_commitment_alerts.yaml'
                  )
                    .replaceAll(
                      '$ACS_COMMITMENT_CHECKPOINT_DELAY_THRESHOLD_SECONDS',
                      monitoringConfig.alerting.alerts.acsCommitments.checkpointDelay.seconds.toString()
                    )
                    .replaceAll(
                      '$ACS_COMMITMENT_DELAY_THRESHOLD_SECONDS',
                      monitoringConfig.alerting.alerts.acsCommitments.completedDelay.seconds.toString()
                    )
                    .replaceAll(
                      '$ACS_COMMITMENT_COMPUTE_DURATION_THRESHOLD_SECONDS',
                      monitoringConfig.alerting.alerts.acsCommitments.computeDuration.seconds.toString()
                    ),
                }),
            'sequencer_connection_pool_alerts.yaml': readGrafanaAlertingFile(
              'sequencer_connection_pool_alerts.yaml'
            ),
            'dso_missed_confirmations_alerts.yaml': substituteDsoMissedConfirmationsAlerts(
              readGrafanaAlertingFile('dso_missed_confirmations_alerts.yaml')
            ),
            'scan_connection_disagreement_alerts.yaml': substituteScanConnectionDisagreementAlerts(
              readGrafanaAlertingFile('scan_connection_disagreement_alerts.yaml')
            ),
            'scan_bft_sequencers_alerts.yaml': readGrafanaAlertingFile(
              'scan_bft_sequencers_alerts.yaml'
            ),
            'global-sync-health_alerts.yaml': readGrafanaAlertingFile(
              'global-sync-health_alerts.yaml'
            )
              .replaceAll(
                '$DISCARDED_CONFIRMATION_REQUESTS_THRESHOLD',
                monitoringConfig.alerting.alerts.globalSynchronizerHealth.discardedConfirmationRequestsThreshold.toString()
              )
              .replaceAll(
                '$FAILED_CONFIRMATION_REQUESTS_THRESHOLD',
                monitoringConfig.alerting.alerts.globalSynchronizerHealth.failedConfirmationRequestsThreshold.toString()
              )
              .replaceAll(
                '$TPS_DROP_THRESHOLD',
                monitoringConfig.alerting.alerts.globalSynchronizerHealth.tpsDropThreshold.toString()
              )
              .replaceAll(
                '$PRIORITY',
                monitoringConfig.alerting.enableExtraHighPrioAlerts ? 'high' : 'medium'
              ),
            'extra_k8s_alerts.yaml': readGrafanaAlertingFile('extra_k8s_alerts.yaml'),
            'sequencer_rate_limit_alerts.yaml': readGrafanaAlertingFile(
              'sequencer_rate_limit_alerts.yaml'
            )
              .replaceAll(
                '$SEQUENCER_RATE_LIMIT_REJECTION_RATE_THRESHOLD',
                monitoringConfig.alerting.alerts.sequencerRateLimits.rejectionRateThreshold.toString()
              )
              .replaceAll(
                '$SEQUENCER_RATE_LIMIT_REJECTION_RATE_PRIORITY',
                monitoringConfig.alerting.enableExtraHighPrioAlerts ? 'high' : 'medium'
              )
              .replaceAll(
                '$SEQUENCER_RATE_LIMIT_CIRCUIT_BREAKER_STATE_THRESHOLD',
                monitoringConfig.alerting.alerts.sequencerRateLimits.circuitBreakerStateThreshold.toString()
              ),
            ...(cantonBftEnabled
              ? {
                  'cantonbft_alerts.yaml': readGrafanaAlertingFile(
                    'cantonbft_alerts.yaml'
                  ).replaceAll(
                    '$CANTON_BFT_MEMPOOL_SIZE_THRESHOLD',
                    monitoringConfig.alerting.alerts.cantonBft.mempoolMaxSizeThreshold.toString()
                  ),
                }
              : {}),
            'deleted_alerts.yaml': readGrafanaAlertingFile('deleted.yaml'),
            'templates.yaml': substituteSlackNotificationTemplate(
              readGrafanaAlertingFile('templates.yaml')
            ),
            'wallet-sweep_alerts.yaml': readAndSetAlertRulesGrafanaAlertingFile(
              'wallet-sweep_alerts.yaml',
              sweepConfigs.map((config, i) => {
                const fromParty = partyIdTransform(config.fromParty);
                const toParty = partyIdTransform(config.toParty);
                return {
                  subtitle: `Wallet sweep from ${fromParty.hint} to ${toParty.hint}`,
                  ownerPrefixRegex: fromParty.regex,
                  // trigger if it goes above 10% of the defined maxBalance
                  maxBalanceThreshold: `${config.maxBalance * monitoringConfig.alerting.alerts.walletSweep.tolerance}`,
                  uid: `df6rim37tocud${i}`,
                };
              })
            ),
            'traffic_based_rewards_alerts.yaml': readGrafanaAlertingFile(
              'traffic_based_rewards_alerts.yaml'
            )
              .replaceAll(
                '$FEATURED_APP_RIGHTS_LIVE_ROW_LIMIT',
                monitoringConfig.alerting.alerts.trafficBasedRewards.featuredAppRightsLimit.toString()
              )
              .replaceAll(
                '$VERDICT_INGESTION_BATCH_SIZE_THRESHOLD',
                monitoringConfig.alerting.alerts.trafficBasedRewards.verdictIngestionBatchSizeThreshold.toString()
              )
              .replaceAll(
                '$VERDICT_INGESTION_BATCH_SIZE_PENDING_PERIOD_MINUTES',
                monitoringConfig.alerting.alerts.trafficBasedRewards.verdictIngestionBatchSizePendingPeriodMinutes.toString()
              ),
            'istio-rate-limiting_alerts.yaml': readGrafanaAlertingFile(
              'istio-rate-limiting_alerts.yaml'
            ),
            'splice-rate-limiting_alerts.yaml': substituteSpliceRateLimitsAlerts(
              readGrafanaAlertingFile('splice-rate-limiting_alerts.yaml')
            ),
            ...(cloudArmorConfig.enabled
              ? {
                  'cloud-armor_alerts.yaml': substituteCloudArmorAlerts(
                    readGrafanaAlertingFile('cloud-armor_alerts.yaml')
                  ),
                }
              : {}),
          },
        }).map(([k, v]) => [k, defaultAlertSubstitutions(v)])
      ),
    },
    {
      // the sidecar reacts to k8s events, so if it deletes it afterward, as it has the same name it will just delete the file
      deleteBeforeReplace: true,
    }
  );
}

function grafanaAlertNotificationPolicies() {
  const notificationPolicies = [];
  const defaultPolicy = yaml.load(
    readGrafanaAlertingFile('notification_policies/default_slack.yaml')
  ) as Record<string, unknown>;
  for (const interval of monitoringConfig.alerting.muteTimeIntervals) {
    notificationPolicies.push({
      receiver: 'cn-ci-channel-notification',
      object_matchers: interval.objectMatchers,
      mute_time_intervals: [interval.name],
    });
  }
  if (enableAlertEmailToSupportTeam) {
    notificationPolicies.push(
      yaml.load(readGrafanaAlertingFile('notification_policies/support_team_email.yaml'))
    );
  }
  if (slackHighPrioAlertNotificationChannel) {
    notificationPolicies.push(
      yaml.load(readGrafanaAlertingFile('notification_policies/high_priority_slack.yaml'))
    );
  }
  // The notification policy definition was implemented in this slightly convoluted manner to ensure the generated YAML
  // is the same as the static files it replaced (to avoid breaking the support team email notifications)
  if (notificationPolicies.length > 0) {
    return yaml.dump({
      apiVersion: 1,
      policies: [
        {
          orgId: 1,
          receiver: (defaultPolicy as { receiver: string }).receiver,
          routes: notificationPolicies.concat(defaultPolicy),
        },
      ],
    });
  } else {
    // we can't have a single policy with matchers, the root policy must be the default if nothing else matches
    delete defaultPolicy.object_matchers;
    return yaml.dump({
      apiVersion: 1,
      policies: [defaultPolicy],
    });
  }
}

function readGrafanaAlertingFile(file: string) {
  const fileContent = fs.readFileSync(
    `${SPLICE_ROOT}/cluster/pulumi/observability/grafana-alerting/${file}`,
    'utf-8'
  );
  return ignoreNoDataOrDataSourceErrorIfNeeded(fileContent);
}

// Ignore no data or data source error if the cluster is reset periodically
function ignoreNoDataOrDataSourceErrorIfNeeded(alerts: string): string {
  return shouldIgnoreNoDataOrDataSourceError
    ? alerts.replace(/(execErrState|noDataState): .+/g, '$1: OK')
    : alerts;
}

type ReportMatchOperator = '=~' | '!~';
type ReportPublisherList = 'Digital-Asset-1|Digital-Asset-2|DA-Helm-Test-Node';
type ReportPublisherFormula = `${ReportMatchOperator}"${ReportPublisherList}"`;
type NotificationDelay = '5m' | '15m';
type TeamLabel = 'canton-network' | 'support' | 'da';
type RulesUID = 'adlmhpz5iv4sgc' | 'bdlmhpz5iv4sgc' | 'cdlmhpz5iv4sgc' | `df6rim37tocud${number}`;

interface AlertRulesConfig {
  reportPublisherFormula?: ReportPublisherFormula;
  notificationDelay?: NotificationDelay;
  teamLabel?: TeamLabel;
  subtitle?: string;
  uid?: RulesUID;
  ownerPrefixRegex?: string;
  maxBalanceThreshold?: string;
  priority?: 'high' | 'medium' | 'low';
}

interface GrafanaRule {
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  [key: string]: any;
}

interface GrafanaRuleGroup {
  name: string;
  rules: GrafanaRule[];
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  [key: string]: any;
}

interface GrafanaRuleFile {
  groups: GrafanaRuleGroup[];
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  [key: string]: any;
}

// reads grafana alerting template rule from file, fill it with alert rules custom configurations and append to rules
function readAndSetAlertRulesGrafanaAlertingFile(file: string, rules: AlertRulesConfig[]) {
  const fileContent = fs.readFileSync(
    `${SPLICE_ROOT}/cluster/pulumi/observability/grafana-alerting/${file}`,
    'utf-8'
  );

  const content: GrafanaRuleFile = yaml.load(fileContent) as GrafanaRuleFile;
  if (!content?.groups?.[0]?.rules?.[0]) {
    throw new Error(`Invalid or empty rule template, Expected 'groups[0].rules[0]' to exist.`);
  }

  const genericAlertRule = yaml.dump(content.groups[0].rules[0]);

  content.groups[0].rules = rules.map(rule => {
    const newRuleString = genericAlertRule
      .replaceAll('$REPORT_PUBLISHER_FORMULA', rule.reportPublisherFormula ?? 'NOT_REPLACED')
      .replaceAll('$NOTIFICATION_DELAY', rule.notificationDelay ?? 'NOT_REPLACED')
      .replaceAll('$TEAM_LABEL', rule.teamLabel ?? 'NOT_REPLACED')
      .replaceAll('$SUB_TITLE', rule.subtitle ?? 'NOT_REPLACED')
      .replaceAll('$RULE_UID', rule.uid ?? 'NOT_REPLACED')
      .replaceAll('$OWNER_PREFIX_REGEX', rule.ownerPrefixRegex ?? 'NOT_REPLACED')
      .replaceAll('$MAX_BALANCE_THRESHOLD', rule.maxBalanceThreshold ?? 'NOT_REPLACED')
      .replaceAll('$PRIORITY', rule.priority ?? 'medium');
    return yaml.load(newRuleString) as GrafanaRule;
  });
  const newFileContent = yaml.dump(content);

  return ignoreNoDataOrDataSourceErrorIfNeeded(newFileContent);
}

function readAlertingManagerFile(file: string) {
  return fs.readFileSync(
    `${SPLICE_ROOT}/cluster/pulumi/observability/alert-manager/${file}`,
    'utf-8'
  );
}

function grafanaKeysFromSecret(): pulumi.Output<GrafanaKeys> {
  const keyJson = getSecretVersionOutput({ secret: 'grafana-keys' });
  return keyJson.apply(k => {
    const secretData = k.secretData;
    const parsed = JSON.parse(secretData);
    return {
      adminUser: String(parsed.adminUser),
      adminPassword: String(parsed.adminPassword),
    };
  });
}

function installPostgres(namespace: ExactNamespace): Postgres {
  const instanceName = 'grafana-postgres';
  return installSplicePostgres(
    namespace,
    instanceName,
    'grafana-postgres-secret',
    monitoringConfig.grafanaPostgres,
    undefined, // chart version
    { disableProtection: true },
    { db: { volumeSize: '20Gi' } }, // A tiny pvc should be enough for grafana
    true, // overrideDbSizeFromValues
    true // useinfraKubernetesScheduling
  );
}
