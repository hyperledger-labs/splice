// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { config } from 'splice-pulumi-common';

import { installFluentBit } from './fluentBit';
import { installNodePools } from './nodePools';
import { installStorageClasses } from './storageClasses';

installNodePools();
installStorageClasses();
// This is an env var instead of reading from config.yaml as we also want to read it from cncluster.
if (config.envFlag('SELF_HOSTED_FLUENT_BIT')) {
  installFluentBit();
}
