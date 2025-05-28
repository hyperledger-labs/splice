// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { config } from './config';

export function failOnAppVersionMismatch(): boolean {
  return config.envFlag('FAIL_ON_APP_VERSION_MISMATCH', true);
}
