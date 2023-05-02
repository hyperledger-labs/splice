import * as pulumi from '@pulumi/pulumi';

import { installDocs } from './docs';
import { installClusterIngress } from './ingress';
import { installSplitwell } from './splitwell';
import { installSVC, installSvNode } from './sv';
import { infraStack } from './utils';
import { installValidator } from './validator';

/// Toplevel Chart Installs

function installCluster() {
  const svc = installSVC();

  installSvNode(svc, 'sv-1');
  installSvNode(svc, 'sv-2');
  installSvNode(svc, 'sv-3');
  installSvNode(svc, 'sv-4');
  const validator = installValidator(svc, 'validator1');
  const splitwell = installSplitwell(svc);

  const docs = installDocs();

  installClusterIngress(
    infraStack.getOutput('ingressNs') as pulumi.Output<string>,
    validator,
    splitwell,
    docs
  );
}

installCluster();
