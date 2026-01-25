// Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as k8s from '@pulumi/kubernetes';

import { infraConfig } from './config';

// Automatically duplicates CRs if multiple namespaces given
export function installExtraCustomResources(): void {
  const extraCrs = infraConfig.extraCustomResources;
  Object.entries(extraCrs).forEach(([name, spec]) => {
    if (Array.isArray(spec.metadata?.namespace)) {
      spec.metadata.namespace.forEach((ns: string) => {
        const patchedName = `${ns}-${name}`;
        const patchedSpec = {
          ...spec,
          metadata: {
            ...spec.metadata,
            namespace: ns,
          },
        };
        new k8s.apiextensions.CustomResource(patchedName, patchedSpec);
      });
    } else {
      new k8s.apiextensions.CustomResource(name, spec);
    }
  });
}
