import * as k8s from '@pulumi/kubernetes';
import * as pulumi from '@pulumi/pulumi';
import * as fs from 'fs';
import { local } from '@pulumi/command';
import { Input } from '@pulumi/pulumi';
import { CLUSTER_BASENAME, CLUSTER_DNS_NAME } from 'cn-pulumi-common';
import { REPO_ROOT } from 'cn-pulumi-common';

import { clusterBasename } from './config';
import { createGrafanaDashboards } from './grafana-dashboards';

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
        hosts: [`${name}.${CLUSTER_DNS_NAME}`],
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

const grafanaExternalUrl = `https://grafana.${CLUSTER_DNS_NAME}`;
const enableAlerts = process.env.GCP_CLUSTER_PROD_LIKE == 'true';

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
  const prometheusStack = new k8s.helm.v3.Release('observability-metrics', {
    name: 'prometheus-grafana-monitoring',
    chart: 'kube-prometheus-stack',
    version: '52.1.0',
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
      kubeProxy: {
        enabled: false,
      },
      alertmanager: {
        enabled: false, // not required yet
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
          enableFeatures: ['native-histograms', 'memory-snapshot-on-shutdown'],
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
        },
      },
      grafana: {
        fullnameOverride: 'grafana',
        ingress: {
          enabled: false,
        },
        sidecar: {
          dashboards: {
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
        },
        adminUser: 'cn-admin',
        adminPassword: 'canton_network_!password',
      },
      'kube-state-metrics': {
        fullnameOverride: 'ksm',
      },
      'prometheus-node-exporter': {
        fullnameOverride: 'node-exporter',
      },
    },
  });

  // If the stack version is updated the crd version might need to be upgraded as well, check the release notes https://artifacthub.io/packages/helm/prometheus-community/kube-prometheus-stack
  const prometheusStackCrdVersion = '0.68.0';
  new local.Command(
    `update-prometheus-crd-${prometheusStackCrdVersion}`,
    {
      create: `bash prometheus-crd-update.sh ${prometheusStackCrdVersion}`,
    },
    { dependsOn: prometheusStack }
  );

  istioVirtualService(namespace, 'prometheus', 'prometheus-prometheus', 9090);
  istioVirtualService(namespace, 'grafana', 'grafana', 80);
  // In the observability cluster, we install a version of the dashboards with a filter
  // that prevents running expensive queries when the dashboard just loads
  createGrafanaDashboards(namespaceName, clusterBasename == 'observability');
  // enable the slack alerts only for "prod" clusters
  if (enableAlerts) {
    createGrafanaContactPoints(namespaceName);
  }
  createGrafanaAlerting(namespaceName);
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
          readFile('slack_contact_point.yaml').replaceAll(
            '$SLACK_ACCESS_TOKEN',
            process.env.SLACK_ACCESS_TOKEN as string
          )
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
          ? { 'notification_policies.yaml': readFile('notification_policies.yaml') }
          : {}),
        ...{
          'load-tester_alerts.yaml': readFile('load-tester_alerts.yaml'),
          'cometbft_alerts.yaml': readFile('cometbft_alerts.yaml'),
          'templates.yaml': readFile('templates.yaml')
            .replaceAll('$CLUSTER_BASENAME', CLUSTER_BASENAME)
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

function readFile(file: string) {
  return fs.readFileSync(`${REPO_ROOT}/cluster/pulumi/infra/grafana-alerting/${file}`, 'utf-8');
}
