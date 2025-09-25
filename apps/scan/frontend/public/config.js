// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
window.splice_config = {
  services: {
    scan: {
      // URL of scan backend.
      // Edit this to the cluster you're trying to connect on.
      url: 'https://scan.sv-2.TARGET_HOSTNAME',
    },
  },
  spliceInstanceNames: {
    networkName: 'Splice',
    networkFaviconUrl: 'https://www.hyperledger.org/hubfs/hyperledgerfavicon.png',
    amuletName: 'Amulet',
    amuletNameAcronym: 'AMT',
    nameServiceName: 'Amulet Name Service',
    nameServiceNameAcronym: 'ANS',
  },
};
