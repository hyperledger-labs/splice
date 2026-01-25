// Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { config } from './config';
import { loadYamlFromFile, PUBLIC_CONFIGS_PATH } from './utils';

export const spliceInstanceNames = config.envFlag('ENABLE_CN_INSTANCE_NAMES')
  ? loadYamlFromFile(PUBLIC_CONFIGS_PATH + '/configs/ui-config-values.yaml')
  : {
      spliceInstanceNames: {
        networkName: 'Splice',
        networkFaviconUrl: 'https://www.hyperledger.org/hubfs/hyperledgerfavicon.png',
        amuletName: 'Amulet',
        amuletNameAcronym: 'AMT',
        nameServiceName: 'Amulet Name Service',
        nameServiceNameAcronym: 'ANS',
      },
    };
