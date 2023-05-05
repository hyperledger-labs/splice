import * as k8s from "@pulumi/kubernetes";
import * as pulumi from '@pulumi/pulumi';

export function installCNHelmChart(
    ns: k8s.core.v1.Namespace,
    name: string,
    chartName: string,
    local: boolean,
    version: string = "",
    dependsOn: pulumi.Resource[] = []
) {
    return new k8s.helm.v3.Release(
        name,
        {
            name: name,
            chart: local ? process.env.REPO_ROOT + "/cluster/helm/" + chartName + "/" : chartName,
            namespace: ns.metadata.name,
            version: local ? undefined : version,
            repositoryOpts: local ? undefined : {
                repo: "https://digitalasset.jfrog.io/artifactory/api/helm/canton-network-helm",
                username: process.env.HELM_REPO_USERNAME,
                password: process.env.HELM_REPO_PASSWORD,
            },
        },
        {
            dependsOn: dependsOn.concat([ns])
        }
    )
}

