import { Auth0ProviderOptions } from '@auth0/auth0-react';

const authConfig: Auth0ProviderOptions = {
  domain: process.env.REACT_APP_OAUTH_DOMAIN || 'canton-network-dev.us.auth0.com',
  clientId: process.env.REACT_APP_OAUTH_CLIENT_ID || '5RJeTm41IwUs8VbbnZHxFEPjCX5ojfaK',
  redirectUri: window.location.origin,
};

export const config = {
  auth: authConfig,
  wallet: {
    grpcUrl: process.env.REACT_APP_GRPC_URL || 'http://localhost:6004',
  },
};
