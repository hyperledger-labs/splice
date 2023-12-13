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
    scan: {
      // URL of scan backend.
      // Edit this to the cluster you're trying to connect on.
      url: 'https://scan.sv-1.svc.TARGET_CLUSTER.network.canton.global/api/scan',
    },
    wallet: {
      // URL of the web-ui, used to forward payment workflows to wallet
      uiUrl: 'http://wallet.localhost:3000',
    },
    validator: {
      // URL of the validator app HTTP API
      url: 'http://localhost:5003',
    },
  },
};

export { config };
