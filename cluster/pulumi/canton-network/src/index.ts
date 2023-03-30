import { installDomain, installParticipant } from "./ledger";

import { installSplitwell } from "./splitwell";
import { installValidator } from "./validator";

import { installSVC, installSvNode } from "./sv";
import { installDocs } from "./docs";
import { installClusterIngress } from "./ingress";
import { configureNetwork } from "./network";

/// Toplevel Chart Installs

function installCluster() {
  //configureDNS();

  const svc = installSVC();

  installSvNode(svc, "sv-1");
  installSvNode(svc, "sv-2");
  installSvNode(svc, "sv-3");
  installSvNode(svc, "sv-4");
  const validator = installValidator(svc, "validator1");
  const splitwell = installSplitwell(svc);

  const docs = installDocs();
  //installClusterIngress(validator, splitwell, docs);
}

//configureNetwork();
installCluster();
