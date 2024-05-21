import * as k8s from '@pulumi/kubernetes';
import * as pulumi from '@pulumi/pulumi';
import * as grafana from '@pulumiverse/grafana';
import * as fs from 'fs';
import { local } from '@pulumi/command';
import { getSecretVersionOutput } from '@pulumi/gcp/secretmanager/getSecretVersion';
import { Input } from '@pulumi/pulumi';
import {
  CLUSTER_BASENAME,
  CLUSTER_NAME,
  CLUSTER_HOSTNAME,
  GCP_PROJECT,
  GrafanaKeys,
  publicPrometheusRemoteWrite,
  REPO_ROOT,
  config,
  EXPECTED_MAX_BLOCK_RATE_PER_SECOND,
  ENABLE_COMETBFT_PRUNING,
  COMETBFT_RETAIN_BLOCKS,
} from 'cn-pulumi-common';

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

function istioPrometheusRemoteWriteVirtualService(
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
        hosts: [`public.${CLUSTER_HOSTNAME}`],
        gateways: ['cluster-ingress/cn-public-http-gateway'],
        http: [
          {
            match: [{ uri: { prefix: '/api/v1/write' }, port: 443 }],
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
const enableAlerts = config.envFlag('GCP_CLUSTER_PROD_LIKE');
const disablePrometheusAlerts = config.envFlag('GCP_CLUSTER_RESET_PERIODICALLY');
const shouldIgnoreNoDataOrDataSourceError = config.envFlag('GCP_CLUSTER_RESET_PERIODICALLY');
const slackAlertNotificationChannel =
  config.optionalEnv('SLACK_ALERT_NOTIFICATION_CHANNEL') || 'C064MTNQT88';

export function configureObservability(dependsOn: pulumi.Resource[] = []): void {
  const namespace = new k8s.core.v1.Namespace(
    'observabilty',
    {
      metadata: {
        name: 'observability',
      },
    },
    { dependsOn }
  );
  const namespaceName = namespace.metadata.name;
  // If the stack version is updated the crd version might need to be upgraded as well, check the release notes https://artifacthub.io/packages/helm/prometheus-community/kube-prometheus-stack
  const stackVersion = '58.5.1';
  const prometheusStackCrdVersion = '0.73.2';
  const adminPassword = grafanaKeysFromSecret().adminPassword;
  const prometheusStack = new k8s.helm.v3.Release('observability-metrics', {
    name: 'prometheus-grafana-monitoring',
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
            receiver: enableAlerts && !disablePrometheusAlerts ? 'slack' : 'null',
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
            ...(enableAlerts && !disablePrometheusAlerts
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
                            credentials: config.requireEnv('SLACK_ACCESS_TOKEN'),
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
        },
        templateFiles: {
          'template.tmpl': readAlertingManagerFile('slack-notification.tmpl').replaceAll(
            '$CLUSTER_BASENAME',
            CLUSTER_BASENAME
          ),
        },
      },
      coreDns: {
        enabled: false,
      },
      prometheusOperator: {
        admissionWebhooks: {
          enabled: false,
        },
        tls: {
          enabled: false, // because `admissionWebhooks` are disabled, see: https://github.com/prometheus-community/helm-charts/issues/418
        },
      },
      prometheus: {
        prometheusSpec: {
          // discover all pod/service monitors across all namespaces
          podMonitorSelectorNilUsesHelmValues: false,
          serviceMonitorSelectorNilUsesHelmValues: false,
          enableFeatures: [
            'native-histograms',
            'memory-snapshot-on-shutdown',
            'promql-experimental-functions',
          ],
          enableRemoteWriteReceiver: true,
          retention: '1y',
          retentionSize: '100GB',
          resources: {
            requests: {
              memory: '12Gi',
              cpu: '4',
            },
          },
          storageSpec: {
            volumeClaimTemplate: {
              spec: {
                storageClassName: 'premium-rwo',
                accessModes: ['ReadWriteOnce'],
                resources: {
                  requests: {
                    storage: '200Gi',
                  },
                },
              },
            },
          },
          externalUrl: prometheusExternalUrl,
        },
      },
      grafana: {
        fullnameOverride: 'grafana',
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
                  metricNamePrefix: 'cn_deployment_flux',
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
                  metricNamePrefix: 'cn_deployment_pulumi',
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
      },
      'prometheus-node-exporter': {
        fullnameOverride: 'node-exporter',
      },
    },
  });

  new local.Command(
    `update-prometheus-crd-${prometheusStackCrdVersion}`,
    {
      create: `bash prometheus-crd-update.sh ${prometheusStackCrdVersion}`,
    },
    { dependsOn: prometheusStack }
  );

  istioVirtualService(namespace, 'prometheus', 'prometheus-prometheus', 9090);
  if (publicPrometheusRemoteWrite) {
    istioPrometheusRemoteWriteVirtualService(
      namespace,
      'prometheus-remote-write',
      'prometheus-prometheus',
      9090
    );
  }
  istioVirtualService(namespace, 'grafana', 'grafana', 80);
  istioVirtualService(namespace, 'alertmanager', 'prometheus-alertmanager', 9093);
  // In the observability cluster, we install a version of the dashboards with a filter
  // that prevents running expensive queries when the dashboard just loads
  createGrafanaDashboards(namespaceName);
  // enable the slack alerts only for "prod" clusters
  if (enableAlerts) {
    createGrafanaContactPoints(namespaceName);
  }
  createGrafanaAlerting(namespaceName);
  createGrafanaServiceAccount(namespaceName, adminPassword);
}

function createGrafanaServiceAccount(
  namespace: Input<string>,
  adminPassword: pulumi.Output<string>
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

function createGrafanaContactPoints(namespace: Input<string>) {
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
        'slackContactPoint.yaml': Buffer.from(
          readGrafanaAlertingFile('slack_contact_point.yaml')
            .replaceAll('$SLACK_ACCESS_TOKEN', config.requireEnv('SLACK_ACCESS_TOKEN'))
            .replaceAll('$SLACK_NOTIFICATION_CHANNEL', slackAlertNotificationChannel)
        ).toString('base64'),
      },
    },
    {
      // the sidecar reacts to k8s events, so if it deletes it afterward, as it has the same name it will just delete the file
      deleteBeforeReplace: true,
    }
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
      data: {
        ...(enableAlerts
          ? { 'notification_policies.yaml': readGrafanaAlertingFile('notification_policies.yaml') }
          : {}),
        ...{
          'deployment_alerts.yaml': readGrafanaAlertingFile('deployment_alerts.yaml'),
          'load-tester_alerts.yaml': readGrafanaAlertingFile('load-tester_alerts.yaml'),
          'cometbft_alerts.yaml': readGrafanaAlertingFile('cometbft_alerts.yaml')
            .replaceAll('$EXPECTED_MAX_BLOCK_RATE_PER_SECOND', EXPECTED_MAX_BLOCK_RATE_PER_SECOND)
            .replaceAll('$ENABLE_COMETBFT_PRUNING', (!ENABLE_COMETBFT_PRUNING).toString())
            .replaceAll('$COMETBFT_RETAIN_BLOCKS', String(Number(COMETBFT_RETAIN_BLOCKS) * 1.05)),
          'automation_alerts.yaml': readGrafanaAlertingFile('automation_alerts.yaml'),
          'sv-status-report_alerts.yaml': readGrafanaAlertingFile('sv-status-report_alerts.yaml'),
          'extra_k8s_alerts.yaml': readGrafanaAlertingFile('extra_k8s_alerts.yaml'),
          'deleted_alerts.yaml': readGrafanaAlertingFile('deleted.yaml'),
          'templates.yaml': readGrafanaAlertingFile('templates.yaml')
            .replaceAll('$CLUSTER_BASENAME', CLUSTER_BASENAME)
            .replaceAll('$CLUSTER_NAME', CLUSTER_NAME)
            .replaceAll('$GCP_PROJECT', GCP_PROJECT)
            .replaceAll('$GRAFANA_EXTERNAL_URL', grafanaExternalUrl),
        },
      },
    },
    {
      // the sidecar reacts to k8s events, so if it deletes it afterward, as it has the same name it will just delete the file
      deleteBeforeReplace: true,
    }
  );
}

function readGrafanaAlertingFile(file: string) {
  const fileContent = fs.readFileSync(
    `${REPO_ROOT}/cluster/pulumi/infra/grafana-alerting/${file}`,
    'utf-8'
  );
  // Ignore no data or data source error if the cluster is reset periodically
  return shouldIgnoreNoDataOrDataSourceError
    ? fileContent.replace(/(execErrState|noDataState): .+/g, '$1: OK')
    : fileContent
        .replace(/noDataState: .+/g, 'noDataState: NoData')
        .replace(/execErrState: .+/g, 'execErrState: Error');
}

function readAlertingManagerFile(file: string) {
  return fs.readFileSync(`${REPO_ROOT}/cluster/pulumi/infra/alert-manager/${file}`, 'utf-8');
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
