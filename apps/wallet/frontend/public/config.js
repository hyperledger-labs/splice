window.canton_network_config = {
    // Auth0 client configuration, see https://github.com/auth0/auth0-spa-js
    auth: {
        domain: 'canton-network-dev.us.auth0.com',
        clientId: '5RJeTm41IwUs8VbbnZHxFEPjCX5ojfaK',
        redirectUri: window.location.origin,
    },
    wallet: {
        // URL of the gRPC-Web envoy proxy, proxying to the wallet app gRPC API
        grpcUrl: 'http://localhost:6004',
    }
}
