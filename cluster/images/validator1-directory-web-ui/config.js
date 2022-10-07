window.canton_network_config = {
    // Auth0 client configuration, see https://github.com/auth0/auth0-spa-js
    auth: {
        domain: 'canton-network-dev.us.auth0.com',
        clientId: '5RJeTm41IwUs8VbbnZHxFEPjCX5ojfaK',
        redirectUri: window.location.origin,
    },
    directory: {
        // URL of the gRPC-Web envoy proxy, proxying to the directory gRPC API
        grpcUrl: 'http://' + window.location.hostname + ':6010',
    },
    ledgerApi: {
        // URL of the gRPC-Web envoy proxy, proxying the user’s ledger API
        grpcUrl: 'http://' + window.location.hostname + ':6101',
    }
}
