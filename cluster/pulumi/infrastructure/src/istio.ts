import * as k8s from "@pulumi/kubernetes";

function configureIstioBase(ns: k8s.core.v1.Namespace): k8s.helm.v3.Release {
  return new k8s.helm.v3.Release(
    "istio-base",
    {
      name: "istio-base",
      chart: "base",
      namespace: ns.metadata.name,
      repositoryOpts: {
        repo: "https://istio-release.storage.googleapis.com/charts",
      },
    },
    {
      dependsOn: [ns],
    }
  );
}

function configureIstiod(
  ingressNs: k8s.core.v1.Namespace,
  base: k8s.helm.v3.Release
): k8s.helm.v3.Release {
  return new k8s.helm.v3.Release(
    "istiod",
    {
      name: "istiod",
      chart: "istiod",
      namespace: ingressNs.metadata.name,
      repositoryOpts: {
        repo: "https://istio-release.storage.googleapis.com/charts",
      },
      values: {
        global: {
          istioNamespace: "cluster-ingress",
        },
      },
    },
    {
      dependsOn: [ingressNs, base],
    }
  );
}

export function configureIstio(
  ingressNs: k8s.core.v1.Namespace
): k8s.helm.v3.Release {
  const nsName = "istio-system";
  const istioSystemNs = new k8s.core.v1.Namespace(nsName, {
    metadata: {
      name: nsName,
    },
  });
  const base = configureIstioBase(istioSystemNs);
  return configureIstiod(ingressNs, base);
}
