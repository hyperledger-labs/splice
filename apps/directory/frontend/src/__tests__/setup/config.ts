// TODO(#7579) -- remove duplication from default config
const config = {
  auth: {
    algorithm: 'hs-256-unsafe',
    secret: 'test',
    token_audience: 'https://canton.network.global',
  },
  // OIDC client configuration, see https://authts.github.io/oidc-client-ts/interfaces/UserManagerSettings.html
  // auth: {
  //   algorithm: 'rs-256',
  //   authority: "",
  //   client_id: "",
  //   token_audience: 'https://validator.example.com/api',
  // },
  services: {
    // BEGIN_DIRECTORY_CONFIG
    directory: {
      // URL of the directory backend
      // Edit this to the cluster you're trying to connect on.
      url: 'https://directory.sv-1.svc.TARGET_CLUSTER.network.canton.global/api/v0/directory',
    },
    // END_DIRECTORY_CONFIG
    wallet: {
      // URL of the web-ui, used to forward payment workflows to wallet
      uiUrl: 'http://wallet.localhost:3000',
    },
    validator: {
      // URL of the validator app HTTP API
      url: 'http://localhost:5003',
    },
    jsonApi: {
      // URL of the JSON API for the participant
      url: 'http://' + window.location.host + '/api/json-api/',
    },
  },
};

export { config };
