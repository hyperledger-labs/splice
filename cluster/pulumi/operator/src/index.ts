import { gitRepoForRef } from 'splice-pulumi-common/src/operator/flux-source';
import { createEnvRefs } from 'splice-pulumi-common/src/operator/stack';

import { operatorDeploymentConfig } from './config';
import { flux } from './flux';
import { namespace } from './namespace';
import { installDeploymentStack } from './stacks/deployment';

const deploymentStackReference = gitRepoForRef(
  'deployment',
  operatorDeploymentConfig.reference,
  false, // no notifications since this typically follows `main` and is too noisy
  [flux]
);
const envRefs = createEnvRefs('operator-env', namespace.logicalName);
installDeploymentStack(deploymentStackReference, envRefs);
