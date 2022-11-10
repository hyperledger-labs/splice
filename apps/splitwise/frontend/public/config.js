window.canton_network_config = {
  auth: {
    algorithm: 'hs-256-unsafe',
    secret: 'test',
  },
  // Auth0 client configuration, see https://github.com/auth0/auth0-spa-js
  // auth: {
  //     algorithm: 'rs-256'
  //     domain: 'canton-network-dev.us.auth0.com',
  //     clientId: '5RJeTm41IwUs8VbbnZHxFEPjCX5ojfaK',
  //     redirectUri: window.location.origin,
  // },
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
};
