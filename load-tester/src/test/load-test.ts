import http from 'k6/http';

import opts from '../options';
import { Auth0Manager } from '../utils/auth';

export const options = opts;

type Data = {
  token: string;
};

export function setup(): Data {
  const oauth = new Auth0Manager(__ENV.AUTH0_DOMAIN, __ENV.AUTH0_CLIENT_ID, __ENV.WALLET_URL);

  // all VUs sharing one user token for now
  const token = oauth.authorizationCodeGrant(__ENV.LOAD_TEST_USER);

  http.post(`${__ENV.WALLET_URL}/api/validator/v0/register`, null, {
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${token}`,
    },
  });

  return { token };
}

export default function (data: Data): void {
  http.post(`${__ENV.WALLET_URL}/api/validator/v0/wallet/tap`, JSON.stringify({ amount: '10.0' }), {
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${data.token}`,
    },
  });
}
