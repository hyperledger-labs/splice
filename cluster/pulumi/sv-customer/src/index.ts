import * as pulumi from "@pulumi/pulumi";
import * as k8s from "@pulumi/kubernetes";
import { configureSecrets } from "./secrets";
import { installCNHelmChart } from "./helm";

// Note that for now this assumes the entire cluster is under this scripts's control,
// i.e. it was only initialized with the `infrastructure` pulumi, no other `cncluster` scripts (specifically, no other secrets or namespaces created).

const svNamespace = new k8s.core.v1.Namespace(
    "sv-1",
    {
        metadata: {
            name: "sv-1",
        }
    }
)

// TODO(#4521): make the local vs repo configurable
const localCharts = false
const version = "0.1.1-snapshot.20230504.2281.0.e6f9ad9b"

const secrets = configureSecrets(svNamespace) // TODO(#4521): create through dependencies from the charts that actually need them

const postgres = installCNHelmChart(
    svNamespace,
    "postgres",
    "cn-postgres",
    localCharts,
    version
)

