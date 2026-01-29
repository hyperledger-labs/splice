// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as pulumi from '@pulumi/pulumi';

export function collectResources<Result>(
  initializeResources: () => Result
): Promise<[Awaited<Result>, Array<pulumi.Resource>]> {
  const resources: Array<pulumi.Resource> = [];
  return new Promise((resolve, reject) => {
    try {
      new pulumi.runtime.Stack(async () => {
        pulumi.runtime.registerStackTransformation(args => {
          resources.push(args.resource);
          return undefined;
        });
        const result = await initializeResources();
        resolve([result, resources]);
        return {};
      });
    } catch (error) {
      reject(error);
    }
  });
}
