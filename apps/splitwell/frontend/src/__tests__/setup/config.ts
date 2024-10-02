// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

// TODO(#7579) -- remove duplication from default config

const config = {
  auth: {
    algorithm: 'hs-256-unsafe',
    secret: 'test',
    token_audience: 'https://ledger_api.example.com',
    token_scope: 'daml_ledger_api',
  },
  // OIDC client configuration, see https://authts.github.io/oidc-client-ts/interfaces/UserManagerSettings.html
  //   auth: {
  //     algorithm: 'rs-256',
  //     authority: "",
  //     client_id: "",
  //     token_audience: 'https://ledger_api.example.com',
  //     token_scope: 'daml_ledger_api',
  //   },
  spliceInstanceNames: {
    amuletName: 'Teluma',
    amuletNameAcronym: 'TLM',
    nameServiceName: 'Teluma Name Service',
    nameServiceNameAcronym: 'TNS',
    networkFaviconUrl: 'https://www.hyperledger.org/hubfs/hyperledgerfavicon.png',
    networkName: 'Ecilps',
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

export { config };
