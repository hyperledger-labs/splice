// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { awaitAllOrThrowAllExceptions, PulumiAbortController } from '../pulumi';
import { pulumiOptsForSv, runSvProjectForAllSvs } from './pulumi';

const abortController = new PulumiAbortController();

awaitAllOrThrowAllExceptions(
  runSvProjectForAllSvs(
    'up',
    async (stack, sv) => {
      console.log(`Updating stack for ${sv}`);
      const pulumiOpts = pulumiOptsForSv(sv, abortController.signal);
      await stack.refresh(pulumiOpts).catch(err => {
        abortController.abort();
        throw err;
      });
      const result = await stack.up(pulumiOpts).catch(err => {
        abortController.abort();
        throw err;
      });
      console.log(`Updated stack for ${sv}`);
      console.log(JSON.stringify(result.summary));
    },
    false
  )
).catch(err => {
  console.error('Failed to run up');
  console.error(err);
  process.exit(1);
});
