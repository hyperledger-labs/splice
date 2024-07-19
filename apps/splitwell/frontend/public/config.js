// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
window.splice_config = {
  auth: {
    algorithm: 'hs-256-unsafe',
    secret: 'test',
    token_audience: 'https://canton.network.global',
    token_scope: 'daml_ledger_api',
  },
  // OIDC client configuration, see https://authts.github.io/oidc-client-ts/interfaces/UserManagerSettings.html
  //   auth: {
  //     algorithm: 'rs-256',
  //     authority: "",
  //     client_id: "",
  //     token_audience: 'https://canton.network.global',
  //     token_scope: 'daml_ledger_api',
  //   },
  spliceInstanceNames: {
    networkName: 'Canton Network',
    networkFaviconUrl: 'https://www.canton.network/hubfs/cn-favicon-05%201-1.png',
    amuletName: 'Canton Coin',
    amuletNameAcronym: 'CC',
    nameServiceName: 'Canton Name Service',
    nameServiceNameAcronym: 'CNS',
  },
  services: {
    wallet: {
      uiUrl: 'http://wallet.localhost:3000',
    },
    jsonApi: {
      // URL of the JSON API for the participant
      url: 'http://' + window.location.host + '/api/json-api/',
    },
    // BEGIN_SPLITWELL_CLUSTER_BACKEND_CONFIG
    splitwell: {
      // URL of the splitwell backend.
      // Edit this to the cluster you're trying to connect on.
      url: 'https://splitwell.TARGET_HOSTNAME/api/splitwell',
    },
    scan: {
      // URL of scan backend.
      // Edit this to the cluster you're trying to connect on.
      url: 'https://scan.sv-2.TARGET_HOSTNAME/api/scan',
    },
    // END_SPLITWELL_CLUSTER_BACKEND_CONFIG
  },
};
