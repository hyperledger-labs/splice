window.canton_network_config = {
    // Auth0 client configuration, see https://github.com/auth0/auth0-spa-js
    auth: {
        domain: 'canton-network-dev.us.auth0.com',
        clientId: '5RJeTm41IwUs8VbbnZHxFEPjCX5ojfaK',
        redirectUri: window.location.origin,
    },
    splitwise: {
        // URL of the gRPC-Web envoy proxy, proxying to the splitwise gRPC API
        grpcUrl: 'http://localhost:8082',
    },
    ledgerApi: {
        // URL of the gRPC-Web envoy proxy, proxying the user’s ledger API
        grpcUrl: 'http://localhost:8085',
    },
    directory: {
        // URL of the gRPC-Web envoy proxy, proxying to the directory gRPC API
        grpcUrl: 'http://localhost:8084',
    },
    scan: {
        // URL of the gRPC-Web envoy proxy, proxying to the scan app gRPC API
        grpcUrl: 'http://localhost:8083',
    }
}
