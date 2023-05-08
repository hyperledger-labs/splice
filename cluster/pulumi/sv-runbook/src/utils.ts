import * as k8s from "@pulumi/kubernetes";

// TODO(#4584): reduce duplication with canton-network project
// There is no way to read the logical name off a Namespace.  Exactly
// specified namespaces are therefore returned as a tuple with the
// logical name, to allow it to be used to ensure distinct Pulumi
// logical names when creating objects of the same name in different
// Kubernetes namespaces.
//
// See: https://github.com/pulumi/pulumi/issues/5234
export interface ExactNamespace {
  ns: k8s.core.v1.Namespace;
  logicalName: string;
}

export function exactNamespace(name: string): ExactNamespace {
  // Namespace with a fully specified name, exactly as it will
  // appear within Kubernetes. (No Pulumi suffix.)
  const ns = new k8s.core.v1.Namespace(name, {
    metadata: {
      name,
    },
  });

  return { ns, logicalName: name };
}

export function requiredEnv(varName: string, msg: string): string {
  const val = process.env[varName];
  if (val == undefined || val == "") {
    throw new Error(
      `Missing environment variable ${varName} (should define: ${msg})`
    );
  }
  return val;
}
