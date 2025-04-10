// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as semver from 'semver';

import { AmuletConfig, USD } from '../../daml.js/splice-amulet-0.1.8/lib/Splice/AmuletConfig';

export function supportsVoteEffectivityAndSetConfig(config: AmuletConfig<USD>): boolean {
  const governanceDarsVersion = config.packageConfig.dsoGovernance;
  const dsoGovernanceVoteEffectivityVersion = '0.1.11';
  return semver.gte(governanceDarsVersion, dsoGovernanceVoteEffectivityVersion);
}
