// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

// TODO(#986) -- remove duplication from default config

const config = {
  // HMAC256-based auth with browser self-signed tokens
  auth: {
    algorithm: 'hs-256-unsafe',
    secret: 'test',
    token_audience: 'https://validator.example.com',
  },
  // OIDC client configuration, see https://authts.github.io/oidc-client-ts/interfaces/UserManagerSettings.html
  //   auth: {
  //     algorithm: 'rs-256',
  //     authority: "",
  //     client_id: "",
  //     token_audience: "https://validator.example.com",
  //   },
  services: {
    validator: {
      // URL of the validator app HTTP API
      url: 'http://localhost:5003/api/validator',
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
