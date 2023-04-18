import * as pulumi from "@pulumi/pulumi";

import { installSplitwell } from "./splitwell";
import { installValidator } from "./validator";

import { installSVC, installSvNode } from "./sv";
import { installDocs } from "./docs";
import { installClusterIngress } from "./ingress";

import { infraStack } from "./utils";

/// Toplevel Chart Installs

function installCluster() {
  const svc = installSVC();

  installSvNode(svc, "sv-1");
  installSvNode(svc, "sv-2");
  installSvNode(svc, "sv-3");
  installSvNode(svc, "sv-4");
  const validator = installValidator(svc, "validator1");
  const splitwell = installSplitwell(svc);

  const docs = installDocs();

  installClusterIngress(
    infraStack.getOutput("ingressNs") as pulumi.Output<string>,
    validator,
    splitwell,
    docs
  );
}

installCluster();
