import * as k8s from "@pulumi/kubernetes";
import * as pulumi from "@pulumi/pulumi";

export function installCNHelmChartByNamespaceName(
  ns: pulumi.Output<string>,
  name: string,
  chartName: string,
  values: ChartValues,
  local: boolean,
  version = "",
  dependsOn: pulumi.Resource[] = []
): k8s.helm.v3.Release {
  return new k8s.helm.v3.Release(
    name,
    {
      name: name,
      chart: local
        ? process.env.REPO_ROOT + "/cluster/helm/" + chartName + "/"
        : chartName,
      namespace: ns,
      version: local ? undefined : version,
      repositoryOpts: local
        ? undefined
        : {
            repo: "https://digitalasset.jfrog.io/artifactory/api/helm/canton-network-helm",
            username: process.env.JFROG_USERNAME,
            password: process.env.JFROG_PASSWORD,
          },
      values: {
        ...values,
        imageRepo: local
          ? "us-central1-docker.pkg.dev/da-cn-images/cn-images"
          : undefined,
      },
    },
    {
      dependsOn: dependsOn,
    }
  );
}

export function installCNHelmChart(
  ns: k8s.core.v1.Namespace,
  name: string,
  chartName: string,
  values: ChartValues,
  local: boolean,
  version = "",
  dependsOn: pulumi.Resource[] = []
): k8s.helm.v3.Release {
  return installCNHelmChartByNamespaceName(
    ns.metadata.name,
    name,
    chartName,
    values,
    local,
    version,
    dependsOn.concat([ns])
  );
}

// Typically used for overriding chart values.
// The pulumi documentation also doesn't suggest a better type than this. ¯\_(ツ)_/¯
// eslint-disable-next-line @typescript-eslint/no-explicit-any
type ChartValues = { [key: string]: any };
