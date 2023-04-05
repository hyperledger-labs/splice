window.canton_network_config = {
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
  services: {
    wallet: {
      uiUrl: "http://wallet.localhost:3000",
    },
    ledgerApi: {
      // URL of the gRPC-Web envoy proxy, proxying the user’s ledger API
      url: 'http://localhost:6201',
    },
    // BEGIN_SPLITWELL_CLUSTER_BACKEND_CONFIG
    splitwell: {
      // URL of the splitwell backend.
      // Edit this to the cluster you're trying to connect on.
      url: 'https://splitwell.TARGET_CLUSTER.network.canton.global',
    },
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
    // END_SPLITWELL_CLUSTER_BACKEND_CONFIG
  },
};
