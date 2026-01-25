// Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { awaitAllOrThrowAllExceptions, PulumiAbortController } from '../pulumi';
import { runAllValidatorsUp } from './pulumiUp';

const abortController = new PulumiAbortController();

awaitAllOrThrowAllExceptions(runAllValidatorsUp(abortController)).catch(err => {
  console.error('Failed to run up');
  console.error(err);
  process.exit(1);
});
