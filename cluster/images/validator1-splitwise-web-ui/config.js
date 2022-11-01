window.canton_network_config = {
    // Auth0 client configuration, see https://github.com/auth0/auth0-spa-js
    auth: {
        domain: 'canton-network-dev.us.auth0.com',
        clientId: '5RJeTm41IwUs8VbbnZHxFEPjCX5ojfaK',
        redirectUri: window.location.origin,
    },
    wallet: {
        // URL of the web-ui, used to forward payment workflows to wallet
        uiUrl: window.location.origin.replace('splitwise','wallet')
    },
    splitwise: {
        // URL of the gRPC-Web envoy proxy, proxying the splitwise gRPC API
        grpcUrl: 'http://' + window.location.hostname + ':6213',
    },
    ledgerApi: {
        // URL of the gRPC-Web envoy proxy, proxying the user’s ledger API
        grpcUrl: 'http://' + window.location.hostname + ':6101',
    },
    directory: {
        // URL of the gRPC-Web envoy proxy, proxying to the directory gRPC API
        grpcUrl: 'http://' + window.location.hostname + ':6010',
    },
    scan: {
        // URL of the gRPC-Web envoy proxy, proxying to the scan app gRPC API
        grpcUrl: 'http://' + window.location.hostname + ':6012',
    }
}
