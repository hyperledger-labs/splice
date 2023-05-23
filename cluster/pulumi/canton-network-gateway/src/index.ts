import * as pulumi from "@pulumi/pulumi";
import * as k8s from "@pulumi/kubernetes";
import * as gcp from "@pulumi/gcp";

import * as fs from 'fs';
import { PathLike } from 'fs';

const clusterBasename = process.env.GCP_CLUSTER_BASENAME
const infraStack = new pulumi.StackReference(`infra.${clusterBasename}`);
const ingressNs = infraStack.getOutput("ingressNs")
const ingressIp = infraStack.getOutput("ingressIp")
const istiod = infraStack.getOutput("istiod")

function ingressPort(
    name: string,
    port: number
): { name: string; port: number; targetPort: number; protocol: string } {
    return {
        name: name,
        port: port,
        targetPort: port,
        protocol: "TCP",
    };
}

// Note that despite the helm chart name being "gateway", this does not actually
// deploy an istio "gateway" resource, but rather the istio-ingress LoadBalancer
// service and the istio-ingress pod.
function configureGatewayService(
    ingressNs: pulumi.Output<any>,
    ingressIp: pulumi.Output<any>,
) {
    const networkSettings = loadJsonFromFile(
        process.env.REPO_ROOT + '/cluster/network-settings.json'
    );
    const externalIPRanges = networkSettings.externalIPRanges;
    return new k8s.helm.v3.Release(
        "istio-ingress",
        {
            name: "istio-ingress",
            chart: "gateway",
            namespace: ingressNs,
            repositoryOpts: {
                repo: "https://istio-release.storage.googleapis.com/charts",
            },
            values: {
                service: {
                    loadBalancerIP: ingressIp,
                    loadBalancerSourceRanges: externalIPRanges,
                    ports: [
                        ingressPort("status-port", 15021), // istio default
                        ingressPort("http2", 80),
                        ingressPort("https", 443),
                        ingressPort("grpc-cd-pub-api", 5008),
                        ingressPort("grpc-cd-adm-api", 5009),
                        ingressPort("cd-metrics", 10313),
                        ingressPort("grpc-svcp-adm", 5002),
                        ingressPort("grpc-svcp-lg", 5001),
                        ingressPort("svcp-metrics", 10013),
                        ingressPort("dir-api", 5010),
                        ingressPort("grpc-svc-adm", 5005),
                        ingressPort("scan-api", 5012),
                        ingressPort("grpc-val1-adm", 5102),
                        ingressPort("grpc-val1-lg", 5101),
                        ingressPort("val1-metrics", 10113),
                        ingressPort("val1-lg-gw", 6101),
                        ingressPort("grpc-swd-pub", 5108),
                        ingressPort("grpc-swd-adm", 5109),
                        ingressPort("swd-metrics", 10413),
                        ingressPort("grpc-sw-adm", 5202),
                        ingressPort("grpc-sw-lg", 5201),
                        ingressPort("sw-metrics", 10213),
                        ingressPort("sw-lg-gw", 6201),
                        ingressPort("grpc-sw-api", 5213),
                        ingressPort("sw-api-gw", 6213),
                    ],
                },
            },
        },
    );
}

function configureForwardAll(
    ingressNs: pulumi.Output<any>,
    gwSvc: k8s.helm.v3.Release
) {
    const repo_root = process.env.REPO_ROOT;
    return new k8s.helm.v3.Release(
        "fwd-all",
        {
            name: "fwd-all",
            namespace: ingressNs,
            chart: repo_root + "/cluster/helm/cn-istio-fwd/",
            values: {
                cluster: {
                    basename: clusterBasename,
                },
        },
        },
        {
            //TODO(#4774): not sure this dependency is really required, I think they can be brought up in parallel
            dependsOn: [gwSvc],
        }
    );
}

const gatewaySvc = configureGatewayService(ingressNs, ingressIp)
configureForwardAll(ingressNs, gatewaySvc)



// TODO(#4584): copied from canton-network pulumi project, we should really have shared utils instead
// eslint-disable-next-line @typescript-eslint/no-explicit-any
function loadJsonFromFile(path: PathLike): any {
    try {
        const content = stripJsonComments(fs.readFileSync(path, 'utf8'));

        return JSON.parse(content);
    } catch (e) {
        pulumi.log.error(`could not read JSON from: ${path}`);
        throw e;
    }
}

function stripJsonComments(rawText: string): string {
    const JSON_COMMENT_REGEX = /\\"|"(?:\\"|[^"])*"|(\/\/.*|\/\*[\s\S]*?\*\/|#.*)/g;

    return rawText.replace(JSON_COMMENT_REGEX, (m, g) => (g ? '' : m));
}
