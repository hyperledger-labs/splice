// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

// TODO(#986) -- remove duplication from default config

const config = {
  services: {
    scan: {
      // URL of scan backend.
      // Edit this to the cluster you're trying to connect on.
      url: 'https://scan.sv-2.TARGET_HOSTNAME',
    },
  },
  spliceInstanceNames: {
    amuletName: 'Teluma',
    amuletNameAcronym: 'TLM',
    nameServiceName: 'Teluma Name Service',
    nameServiceNameAcronym: 'TNS',
    networkFaviconUrl: 'https://www.hyperledger.org/hubfs/hyperledgerfavicon.png',
    networkName: 'Ecilps',
  },
};

export { config };
