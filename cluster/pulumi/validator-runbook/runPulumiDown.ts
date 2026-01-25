// Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { PulumiAbortController } from '../pulumi';
import { downAllTheValidatorsStacks } from './pulumiDown';

const abortController = new PulumiAbortController();

downAllTheValidatorsStacks(abortController).catch(e => {
  console.error('Failed to run destroy');
  console.error(e);
  process.exit(1);
});
