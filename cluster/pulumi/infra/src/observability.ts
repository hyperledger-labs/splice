// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as k8s from '@pulumi/kubernetes';
import * as pulumi from '@pulumi/pulumi';
import * as grafana from '@pulumiverse/grafana';
import * as fs from 'fs';
import * as yaml from 'js-yaml';
import {
  CLUSTER_BASENAME,
  CLUSTER_HOSTNAME,
  CLUSTER_NAME,
  clusterProdLike,
  COMETBFT_RETAIN_BLOCKS,
  commandScriptPath,
  ENABLE_COMETBFT_PRUNING,
  ExactNamespace,
  GCP_PROJECT,
  GrafanaKeys,
  HELM_MAX_HISTORY_SIZE,
  isMainNet,
  loadTesterConfig,
  ObservabilityReleaseName,
  SPLICE_ROOT,
} from '@lfdecentralizedtrust/splice-pulumi-common';
import { infraAffinityAndTolerations } from '@lfdecentralizedtrust/splice-pulumi-common';
import { SplicePostgres } from '@lfdecentralizedtrust/splice-pulumi-common/src/postgres';
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
import { infraConfig, monitoringConfig } from './config';
import { createGrafanaDashboards } from './grafana-dashboards';
import { istioVersion } from './istio';

function istioVirtualService(
  ns: k8s.core.v1.Namespace,
  name: string,
  serviceName: string,
  servicePort: number
) {
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
                  host: pulumi.interpolate`${serviceName}.${ns.metadata.name}.svc.cluster.local`,
                  port: {
                    number: servicePort,
                  },
                },
              },
            ],
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

export function configureObservability(dependsOn: pulumi.Resource[] = []): pulumi.Resource {
  const namespaceName = 'observability';
  const namespace = new k8s.core.v1.Namespace(
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
      dependsOn,
      aliases: [
        { name: 'observabilty' }, // Legacy typo
      ],
    }
  );
  // If the stack version is updated the crd version might need to be upgraded as well, check the release notes https://artifacthub.io/packages/helm/prometheus-community/kube-prometheus-stack
  const stackVersion = '77.12.1';
  const prometheusStackCrdVersion = '0.85.0';
  const postgres = installPostgres({ ns: namespace, logicalName: namespaceName });
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
                  matchers: ['alertname="Watchdog"'],
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
                spec: {
                  storageClassName: 'standard-rwo',
                  accessModes: ['ReadWriteOnce'],
                  resources: {
                    requests: {
                      storage: '5Gi',
                    },
                  },
                },
              },
            },
            ...infraAffinityAndTolerations,
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
          ...infraAffinityAndTolerations,
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
            enableFeatures: [
              'native-histograms',
              'memory-snapshot-on-shutdown',
              'promql-experimental-functions',
            ],
            enableRemoteWriteReceiver: true,
            retention: infraConfig.prometheus.retentionDuration,
            retentionSize: infraConfig.prometheus.retentionSize,
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
            storageSpec: {
              volumeClaimTemplate: {
                spec: {
                  storageClassName: 'premium-rwo',
                  accessModes: ['ReadWriteOnce'],
                  resources: {
                    requests: {
                      storage: infraConfig.prometheus.storageSize,
                    },
                  },
                },
              },
            },
            externalUrl: prometheusExternalUrl,
            ...infraAffinityAndTolerations,
          },
        },
        grafana: {
          fullnameOverride: 'grafana',
          envFromSecret: 'grafana-pg-secret',
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
                revision: istioVersion.dashboards.general,
              },
              mesh: {
                gnetId: 7639,
                datasource: 'Prometheus',
                revision: istioVersion.dashboards.general,
              },
              performance: {
                gnetId: 11829,
                datasource: 'Prometheus',
                revision: istioVersion.dashboards.general,
              },
              service: {
                gnetId: 7636,
                datasource: 'Prometheus',
                revision: istioVersion.dashboards.general,
              },
              workload: {
                gnetId: 7630,
                datasource: 'Prometheus',
                revision: istioVersion.dashboards.general,
              },
              wasm: {
                gnetId: 13277,
                datasource: 'Prometheus',
                revision: istioVersion.dashboards.wasm,
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
            storageClassName: 'standard-rwo',
          },
          adminUser: 'cn-admin',
          adminPassword: adminPassword,
          ...infraAffinityAndTolerations,
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
          ...infraAffinityAndTolerations,
        },
        'prometheus-node-exporter': {
          fullnameOverride: 'node-exporter',
        },
        database: {},
      },
      maxHistory: HELM_MAX_HISTORY_SIZE,
    },
    {
      dependsOn: [namespace],
    }
  );

  const path = commandScriptPath('cluster/pulumi/infra/prometheus-crd-update.sh');
  new local.Command(
    `update-prometheus-crd-${prometheusStackCrdVersion}`,
    {
      create: `bash ${path} ${prometheusStackCrdVersion}`,
    },
    { dependsOn: prometheusStack }
  );

  istioVirtualService(namespace, 'prometheus', 'prometheus-prometheus', 9090);
  istioVirtualService(namespace, 'grafana', 'grafana', 80);
  istioVirtualService(namespace, 'alertmanager', 'prometheus-alertmanager', 9093);
  // In the observability cluster, we install a version of the dashboards with a filter
  // that prevents running expensive queries when the dashboard just loads
  createGrafanaDashboards(namespaceName);
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
  if (!isMainNet) {
    createGrafanaServiceAccount(namespaceName, adminPassword, dependsOn.concat([prometheusStack]));
  }
  createGrafanaEnvoyFilter(namespaceName, [prometheusStack]);

  if (infraConfig.prometheus.installPrometheusPushgateway) {
    new k8s.helm.v3.Release('prometheus-pushgateway', {
      name: 'prometheus-pushgateway',
      chart: 'prometheus-pushgateway',
      version: '3.4.1',
      namespace: namespaceName,
      repositoryOpts: {
        repo: 'https://prometheus-community.github.io/helm-charts',
      },
      values: {
        ...infraAffinityAndTolerations,
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

  const serviceAccountResource = new grafana.ServiceAccount(
    'grafanaSA',
    {
      role: 'Editor',
    },
    {
      provider: grafanaProvider,
      dependsOn: [...dependsOn, grafanaProvider],
    }
  );
  const serviceAccountToken = new grafana.ServiceAccountToken(
    'grafanaSAToken',
    {
      serviceAccountId: serviceAccountResource.id,
      name: 'grafana-sa-token',
    },
    {
      provider: grafanaProvider,
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

function createGrafanaAlerting(namespace: Input<string>) {
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
          ...{
            'deployment_alerts.yaml': readGrafanaAlertingFile('deployment_alerts.yaml'),
            'load-tester_alerts.yaml': readGrafanaAlertingFile('load-tester_alerts.yaml')
              .replace(
                '$LOAD_TESTER_MIN_RATE',
                loadTesterConfig?.minRate ? loadTesterConfig?.minRate.toString() : '1.0'
              )
              .replaceAll('$NODATA', loadTesterConfig?.enable ? 'Alerting' : 'OK'),
            'cometbft_alerts.yaml': readGrafanaAlertingFile('cometbft_alerts.yaml')
              .replaceAll(
                '$EXPECTED_MAX_BLOCK_RATE_PER_SECOND',
                monitoringConfig.alerting.alerts.cometbft.expectedMaxBlocksPerSecond.toString()
              )
              .replaceAll('$ENABLE_COMETBFT_PRUNING', (!ENABLE_COMETBFT_PRUNING).toString())
              .replaceAll('$COMETBFT_RETAIN_BLOCKS', String(Number(COMETBFT_RETAIN_BLOCKS) * 1.05)),
            'automation_alerts.yaml': readGrafanaAlertingFile('automation_alerts.yaml').replaceAll(
              '$CONTENTION_THRESHOLD_PERCENTAGE_PER_NAMESPACE',
              monitoringConfig.alerting.alerts.delegatelessContention.thresholdPerNamespace.toString()
            ),
            'sv-status-report_alerts.yaml': readGrafanaAlertingFile('sv-status-report_alerts.yaml'),
            ...(enableMiningRoundAlert
              ? {
                  'mining-rounds_alerts.yaml': readGrafanaAlertingFile('mining-rounds_alerts.yaml'),
                }
              : {}),
            'acknowledgement_alerts.yaml': readGrafanaAlertingFile(
              'acknowledgement_alerts.yaml'
            ).replace(
              '$MEDIATOR_ACKNOWLEDGEMENT_LAG_SECONDS',
              monitoringConfig.alerting.alerts.mediators.acknowledgementLagSeconds.toString()
            ),
            'extra_k8s_alerts.yaml': readGrafanaAlertingFile('extra_k8s_alerts.yaml'),
            'traffic_alerts.yaml': readGrafanaAlertingFile('traffic_alerts.yaml')
              .replaceAll(
                '$CONFIRMATION_REQUESTS_TOTAL_ALERT_TIME_RANGE_MINS',
                monitoringConfig.alerting.alerts.confirmationRequests.total.overMinutes.toString()
              )
              .replaceAll(
                '$CONFIRMATION_REQUESTS_TOTAL_ALERT_THRESHOLD',
                monitoringConfig.alerting.alerts.confirmationRequests.total.rate.toString()
              )
              .replaceAll(
                '$CONFIRMATION_REQUESTS_BY_MEMBER_ALERT_TIME_RANGE_MINS',
                monitoringConfig.alerting.alerts.confirmationRequests.total.overMinutes.toString()
              )
              .replaceAll(
                '$CONFIRMATION_REQUESTS_BY_MEMBER_ALERT_THRESHOLD',
                monitoringConfig.alerting.alerts.confirmationRequests.total.rate.toString()
              ),
            'deleted_alerts.yaml': readGrafanaAlertingFile('deleted.yaml'),
            'templates.yaml': substituteSlackNotificationTemplate(
              readGrafanaAlertingFile('templates.yaml')
            ),
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
  );
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
    return yaml.dump({
      apiVersion: 1,
      policies: [defaultPolicy],
    });
  }
}

function readGrafanaAlertingFile(file: string) {
  const fileContent = fs.readFileSync(
    `${SPLICE_ROOT}/cluster/pulumi/infra/grafana-alerting/${file}`,
    'utf-8'
  );
  // Ignore no data or data source error if the cluster is reset periodically
  return shouldIgnoreNoDataOrDataSourceError
    ? fileContent.replace(/(execErrState|noDataState): .+/g, '$1: OK')
    : fileContent;
}

function readAlertingManagerFile(file: string) {
  return fs.readFileSync(`${SPLICE_ROOT}/cluster/pulumi/infra/alert-manager/${file}`, 'utf-8');
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

function installPostgres(namespace: ExactNamespace): SplicePostgres {
  return new SplicePostgres(
    namespace,
    'grafana-pg',
    'grafana-pg',
    'grafana-pg-secret',
    { db: { volumeSize: '20Gi' } }, // A tiny pvc should be enough for grafana
    true // overrideDbSizeFromValues
  );
}
