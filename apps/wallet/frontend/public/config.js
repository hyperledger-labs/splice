window.canton_network_config = {
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
    wallet: {
      // URL of the wallet app HTTP API
      url: 'http://localhost:5003',
    },
    validator: {
      // URL of the validator app HTTP API
      url: 'http://localhost:5003',
    },
    // BEGIN_WALLET_CLUSTER_BACKEND_CONFIG
    directory: {
      // URL of the directory backend.
      // Edit this to the cluster you're trying to connect on.
      url: 'https://directory.TARGET_CLUSTER.network.canton.global',
    },
    scan: {
      // URL of scan backend.
      // Edit this to the cluster you're trying to connect on.
      url: 'https://scan.TARGET_CLUSTER.network.canton.global',
    },
    // END_WALLET_CLUSTER_BACKEND_CONFIG
  },
};
