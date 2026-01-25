// Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { PulumiAbortController } from '../pulumi';
import { downAllTheCantonStacks } from './pulumiDown';

const abortController = new PulumiAbortController();

downAllTheCantonStacks(abortController).catch(e => {
  console.error('Failed to run destroy');
  console.error(e);
  process.exit(1);
});
