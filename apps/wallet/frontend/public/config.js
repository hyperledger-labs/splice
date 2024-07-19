// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
window.splice_config = {
  // HMAC256-based auth with browser self-signed tokens
  auth: {
    algorithm: 'hs-256-unsafe',
    secret: 'test',
    token_audience: 'https://canton.network.global',
  },
  // OIDC client configuration, see https://authts.github.io/oidc-client-ts/interfaces/UserManagerSettings.html
  //   auth: {
  //     algorithm: 'rs-256',
  //     authority: "",
  //     client_id: "",
  //     token_audience: "https://canton.network.global",
  //     token_scope: "daml_ledger_api",
  //   },
  services: {
    validator: {
      // URL of the validator app HTTP API
      url: 'http://localhost:5003/api/validator',
    },
    // BEGIN_WALLET_CLUSTER_BACKEND_CONFIG
    scan: {
      // URL of scan backend.
      // Edit this to the cluster you're trying to connect on.
      url: 'https://scan.sv-2.TARGET_HOSTNAME/api/scan',
    },
  },
  clusterUrl: `https://TARGET_HOSTNAME`,
  spliceInstanceNames: {
    networkName: 'Canton Network',
    networkFaviconUrl: 'https://www.canton.network/hubfs/cn-favicon-05%201-1.png',
    amuletName: 'Canton Coin',
    amuletNameAcronym: 'CC',
    nameServiceName: 'Canton Name Service',
    nameServiceNameAcronym: 'CNS',
  },
  // END_WALLET_CLUSTER_BACKEND_CONFIG
};
