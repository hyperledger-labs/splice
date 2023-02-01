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
    splitwise: {
      // URL of the gRPC-Web envoy proxy, proxying to the splitwise gRPC API
      grpcUrl: 'http://localhost:6113',
    },
    ledgerApi: {
      // URL of the gRPC-Web envoy proxy, proxying the user’s ledger API
      grpcUrl: 'http://localhost:6201',
    },
    directory: {
      // URL of the gRPC-Web envoy proxy, proxying to the directory gRPC API
      grpcUrl: 'http://localhost:6110',
    },
    scan: {
      // URL of the gRPC-Web envoy proxy, proxying to the scan app gRPC API
      grpcUrl: 'http://localhost:6012',
    },
  },
  domains: {
    // TODO (M3-83) Replace by separate splitwise domain
    splitwise = "global",
  },
};
