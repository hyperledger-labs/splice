// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
window.splice_config = {
  // note that this gets overwritten via environment variables set in `start-frontends.sh`
  auth: {
    algorithm: 'hs-256-unsafe',
    secret: 'test',
    token_audience: 'https://validator.example.com',
  },
  // OIDC client configuration, see https://authts.github.io/oidc-client-ts/interfaces/UserManagerSettings.html
  // auth: {
  //   algorithm: 'rs-256',
  //   authority: "",
  //   client_id: "",
  //   token_audience: 'https://validator.example.com',
  // },
  services: {
    wallet: {
      // URL of the web-ui, used to forward payment workflows to wallet
      uiUrl: 'http://wallet.localhost:3000',
    },
    validator: {
      // URL of the validator app HTTP API
      url: 'http://localhost:5003/api/validator',
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
