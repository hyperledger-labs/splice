// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { PulumiAbortController } from '../pulumi';
import { downAllTheSvStacks } from './pulumiDown';

const abortController = new PulumiAbortController();

downAllTheSvStacks(abortController).catch(e => {
  console.error('Failed to run destroy');
  console.error(e);
  process.exit(1);
});
