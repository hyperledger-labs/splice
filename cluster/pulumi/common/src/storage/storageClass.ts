// Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { hyperdiskSupportConfig } from '../config/hyperdiskSupportConfig';

export const standardStorageClassName = hyperdiskSupportConfig.hyperdiskSupport.enabled
  ? 'hyperdisk-standard-rwo'
  : 'standard-rwo';

export const infraStandardStorageClassName = hyperdiskSupportConfig.hyperdiskSupport.enabled
  ? 'hyperdisk-standard-rwo'
  : 'standard-rwo';

export const infraPremiumStorageClassName = hyperdiskSupportConfig.hyperdiskSupport.enabled
  ? 'hyperdisk-balanced-rwo'
  : 'premium-rwo';
export const pvcSuffix = hyperdiskSupportConfig.hyperdiskSupport.enabled ? 'hd-pvc' : 'pvc';
