window.splice_config = {
  // HMAC256-based auth with browser self-signed tokens
  auth: {
    algorithm: 'hs-256-unsafe',
    secret: 'test',
    token_audience: 'https://validator.example.com',
  },
  // OIDC client configuration, see https://authts.github.io/oidc-client-ts/interfaces/UserManagerSettings.html
  //   auth: {
  //     algorithm: 'rs-256',
  //     authority: "",
  //     client_id: "",
  //     token_audience: "https://validator.example.com",
  //   },
  services: {
    validator: {
      // URL of the validator app HTTP API
      url: 'http://localhost:5003/api/validator',
    },
  },
};
