// Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { config } from './config';

export const initialPackageConfigJson = config.optionalEnv('INITIAL_PACKAGE_CONFIG_JSON');
