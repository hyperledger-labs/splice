import * as k8s from '@pulumi/kubernetes';
import * as pulumi from '@pulumi/pulumi';
import { CLUSTER_DNS_NAME } from 'cn-pulumi-common';

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

export function configureObservability(): void {
  const namespace = new k8s.core.v1.Namespace('observabilty', {
    metadata: {
      name: 'observability',
    },
  });
  const namespaceName = namespace.metadata.name;
  new k8s.helm.v3.Release('observability-metrics', {
    name: 'prometheus-grafana-monitoring',
    chart: 'kube-prometheus-stack',
    version: '48.2.0',
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

  istioVirtualService(namespace, 'prometheus', 'prometheus-prometheus', 9090);
  istioVirtualService(namespace, 'grafana', 'grafana', 80);
  createGrafanaDashboards(namespaceName);
}
