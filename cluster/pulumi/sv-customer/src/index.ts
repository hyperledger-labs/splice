import * as pulumi from "@pulumi/pulumi";
import * as k8s from "@pulumi/kubernetes";
import { configureSecrets } from "./secrets";

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

const secrets = configureSecrets(svNamespace)
