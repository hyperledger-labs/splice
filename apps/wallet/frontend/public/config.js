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
      // URL of the gRPC-Web envoy proxy, proxying to the wallet app gRPC API
      grpcUrl: 'http://localhost:6004',
    },
    validator: {
      // URL of the gRPC-Web envoy proxy, proxying to the validator app gRPC API
      grpcUrl: 'http://localhost:6003',
    },
    directory: {
      // URL of the gRPC-Web envoy proxy, proxying to the directory app gRPC API
      grpcUrl: 'http://localhost:6110',
    },
    scan: {
      // URL of the gRPC-Web envoy proxy, proxying to the scan app gRPC API
      grpcUrl: 'http://localhost:6012',
    },
  },
};
