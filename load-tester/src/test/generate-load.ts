/* @ts-expect-error typings unavailable */
import { randomIntBetween } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';
import { sleep } from 'k6';
import http from 'k6/http';

import settings from '../settings';
import { Auth0Manager } from '../utils/auth';

export const options = { ...settings.options };

type Data = {
  token: string;
};

export function setup(): Data {
  const oauth = new Auth0Manager(
    settings.auth.oauthDomain,
    settings.auth.oauthClientId,
    settings.walletBaseUrl,
  );

  // all VUs sharing one user token for now
  const token = oauth.authorizationCodeGrant(settings.auth.userCredentials);

  http.post(`${settings.walletBaseUrl}/api/validator/v0/register`, null, {
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${token}`,
    },
  });

  return { token };
}

export default function (data: Data): void {
  http.post(
    `${settings.walletBaseUrl}/api/validator/v0/wallet/tap`,
    JSON.stringify({ amount: '10.0' }),
    {
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${data.token}`,
      },
    },
  );

  // target a rate of roughly ~1 tap/sec, based on VU size (10 VUs)
  sleep(randomIntBetween(8, 12));
}
