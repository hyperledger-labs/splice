// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
window.canton_network_config = {
  // URL of SV API.
  // Edit this to match the SV you're trying to connect to.
  // Note that this gets overwritten in `start-frontends.sh`.
  // HMAC256-based auth with browser self-signed tokens
  auth: {
    algorithm: 'hs-256-unsafe',
    secret: 'test',
    token_audience: 'https://canton.network.global',
  },

  services: {
    sv: {
      url: 'http://localhost:5014/api/sv',
    },
  },
};
