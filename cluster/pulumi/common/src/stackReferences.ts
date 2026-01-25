// Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as pulumi from '@pulumi/pulumi';

import { CLUSTER_BASENAME } from './utils';

// Reference to upstream infrastructure stack.
export const infraStack = new pulumi.StackReference(`organization/infra/infra.${CLUSTER_BASENAME}`);
