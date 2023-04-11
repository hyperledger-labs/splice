window.canton_network_config = {
  // ledgerApi auth; we don't need authentication towards the directory backend
  // note that this gets overwritten via environment variables set in `start-frontends.sh`
  auth: {
    algorithm: 'hs-256-unsafe',
    secret: 'test',
    token_audience: 'https://canton.network.global',
    token_scope: 'daml_ledger_api',
  },
  // OIDC client configuration, see https://authts.github.io/oidc-client-ts/interfaces/UserManagerSettings.html
  // auth: {
  //   algorithm: 'rs-256',
  //   authority: "",
  //   client_id: "",
  //   token_audience: 'https://ledger_api.example.com',
  //   token_scope: 'daml_ledger_api',
  // },
  services: {
    // BEGIN_DIRECTORY_CONFIG
    directory: {
      // URL of the directory backend
      // Edit this to the cluster you're trying to connect on.
      url: 'https://directory.TARGET_CLUSTER.network.canton.global',
    },
    // END_DIRECTORY_CONFIG
    wallet: {
      // URL of the web-ui, used to forward payment workflows to wallet
      uiUrl: 'http://wallet.localhost:3000',
    },
    jsonApi: {
      // URL of the JSON API for the participant
      url: 'http://' + window.location.host + '/api/json-api/',
    },
  },
};
