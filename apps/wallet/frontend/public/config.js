window.canton_network_config = {
  // HMAC256-based auth with browser self-signed tokens
  auth: {
    secret: 'test',
  },
  // Auth0 client configuration, see https://github.com/auth0/auth0-spa-js
  //   auth: {
  //     domain: "",
  //     clientId: "",
  //     redirectUri: window.location.origin,
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
