import { ValidatorClient } from '../client/validator';
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

  const token = oauth.authorizationCodeGrant(settings.auth.userCredentials);
  return { token };
}

export default function (data: Data): void {
  const validator = new ValidatorClient(settings.walletBaseUrl, data.token);

  const userStatus = validator.v0.wallet.userStatus();
  if (userStatus?.user_onboarded && userStatus?.user_wallet_installed) {
    validator.v0.wallet.tap('10.0');
  } else {
    validator.v0.register();
  }
}
