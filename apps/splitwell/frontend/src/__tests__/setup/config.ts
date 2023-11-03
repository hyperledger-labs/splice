// TODO(#7579) -- remove duplication from default config
const config = {
  auth: {
    algorithm: 'hs-256-unsafe',
    secret: 'test',
    token_audience: 'https://canton.network.global',
    token_scope: 'daml_ledger_api',
  },
  // OIDC client configuration, see https://authts.github.io/oidc-client-ts/interfaces/UserManagerSettings.html
  //   auth: {
  //     algorithm: 'rs-256',
  //     authority: "",
  //     client_id: "",
  //     token_audience: 'https://canton.network.global',
  //     token_scope: 'daml_ledger_api',
  //   },
  services: {
    wallet: {
      uiUrl: 'http://wallet.localhost:3000',
    },
    jsonApi: {
      // URL of the JSON API for the participant
      url: 'http://' + window.location.host + '/api/json-api/',
    },
    // BEGIN_SPLITWELL_CLUSTER_BACKEND_CONFIG
    splitwell: {
      // URL of the splitwell backend.
      // Edit this to the cluster you're trying to connect on.
      url: 'https://splitwell.TARGET_CLUSTER.network.canton.global',
    },
    directory: {
      // URL of the directory backend.
      // Edit this to the cluster you're trying to connect on.
      url: 'https://directory.sv-1.svc.TARGET_CLUSTER.network.canton.global/api/v0/directory',
    },
    scan: {
      // URL of scan backend.
      // Edit this to the cluster you're trying to connect on.
      url: 'https://scan.sv-1.svc.TARGET_CLUSTER.network.canton.global/api/scan',
    },
    // END_SPLITWELL_CLUSTER_BACKEND_CONFIG
  },
};

export { config };
