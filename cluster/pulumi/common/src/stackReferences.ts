// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as pulumi from '@pulumi/pulumi';

import { CLUSTER_BASENAME } from './utils';

// Reference to upstream infrastructure stack.
export const infraStack = new pulumi.StackReference(`organization/infra/infra.${CLUSTER_BASENAME}`);

export class StackReferences {
  private static refCache: Partial<Record<string, pulumi.StackReference>> = {};

  public static svCanton(sv: string, migrationId: number): pulumi.StackReference {
    const projectName = 'sv-canton';
    const stackName = `${projectName}.${sv}-migration-${migrationId}.${CLUSTER_BASENAME}`;
    return (StackReferences.refCache[stackName] ??= new pulumi.StackReference(
      `organization/${projectName}/${stackName}`
    ));
  }

  private constructor() {}
}
