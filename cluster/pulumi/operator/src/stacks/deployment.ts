// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
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
