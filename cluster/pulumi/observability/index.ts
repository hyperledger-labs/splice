import * as k8s from '@pulumi/kubernetes';
import * as pulumi from '@pulumi/pulumi';
import { CLUSTER_DNS_NAME } from 'cn-pulumi-common';

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
        enableFeatures: ['native-histograms'],
        enableRemoteWriteReceiver: true,
        retention: '1y',
        retentionSize: '100GB',
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
    },
    'kube-state-metrics': {
      fullnameOverride: 'ksm',
    },
    'prometheus-node-exporter': {
      fullnameOverride: 'node-exporter',
    },
  },
});

new k8s.apiextensions.CustomResource('prometheus-virtual-service', {
  apiVersion: 'networking.istio.io/v1alpha3',
  kind: 'VirtualService',
  metadata: {
    name: 'prometheus-api',
    namespace: namespaceName,
  },
  spec: {
    hosts: [pulumi.interpolate`prometheus.${CLUSTER_DNS_NAME}`],
    gateways: ['cluster-ingress/cn-http-gateway'],
    http: [
      {
        match: [{ port: 443 }, { port: 80 }],
        route: [
          {
            destination: {
              host: pulumi.interpolate`prometheus-prometheus.${namespaceName}.svc.cluster.local`,
              port: {
                number: 9090,
              },
            },
          },
        ],
      },
    ],
  },
});
