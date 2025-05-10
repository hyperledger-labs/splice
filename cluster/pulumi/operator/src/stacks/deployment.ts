import { GitFluxRef } from 'splice-pulumi-common/src/operator/flux-source';
import { createStackCR, EnvRefs } from 'splice-pulumi-common/src/operator/stack';

import { flux } from '../flux';
import { namespace } from '../namespace';
import { operator } from '../operator';

export function installDeploymentStack(reference: GitFluxRef, envRefs: EnvRefs): void {
  createStackCR('deployment', 'deployment', false, reference, envRefs, {}, namespace.logicalName, [
    operator,
    flux,
  ]);
}
