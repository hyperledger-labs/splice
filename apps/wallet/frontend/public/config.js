window.canton_network_config = {
  // HMAC256-based auth with browser self-signed tokens
  auth: {
    algorithm: 'hs-256-unsafe',
    secret: 'test',
  },
  // OIDC client configuration, see https://authts.github.io/oidc-client-ts/interfaces/UserManagerSettings.html
  //   auth: {
  //     algorithm: 'rs-256'
  //     authority: "",
  //     client_id: "",
  //     redirect_uri: window.location.origin,
  //   },
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
};
