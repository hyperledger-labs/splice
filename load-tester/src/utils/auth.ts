import { HttpClient } from './http';

function parse(querystr: string): Record<string, string> {
  return querystr.split('&').reduce((prev, current) => {
    const key = current.split('=')[0];
    const val = current.split('=')[1];

    return { ...prev, [key]: val };
  }, {});
}

// While most of this follows a standard oauth2 authorization code grant,
//  parts of it make specific auth0 assumptions
export class Auth0Manager {
  private auth0Tenant: string;
  private clientId: string;

  private walletUri: string;
  private cnAudience: string = 'https://canton.network.global';

  private codeVerifier: string =
    'acodeverifieracodeverifieracodeverifieracodeverifieracodeverifier';

  private httpClient: HttpClient;

  constructor(auth0Tenant: string, clientId: string, walletUri: string) {
    this.auth0Tenant = auth0Tenant;
    this.clientId = clientId;
    this.walletUri = walletUri;
    this.httpClient = new HttpClient(auth0Tenant, true);
  }

  // Step through the /authorize endpoint
  private _authorize = () => {
    console.log('Calling authorize...');

    const redirectUri = this.walletUri;
    const responseType = 'code';
    const scope = 'offline_access';
    const state = '4c3c3e05fcef48b99362127d7e72b5e5';

    // use plain code verification for now. auth0 kept rejecting the SHA256 code challenge method
    const codeChallenge = this.codeVerifier;
    const codeChallengeMethod = 'plain';

    const responseMode = 'query';
    const prompt = 'login';

    const url = encodeURI(
      `${this.auth0Tenant}/authorize?client_id=${this.clientId}&redirect_uri=${redirectUri}&response_type=${responseType}&scope=${scope}&state=${state}&code_challenge=${codeChallenge}&code_challenge_method=${codeChallengeMethod}&response_mode=${responseMode}&prompt=${prompt}&audience=${this.cnAudience}`,
    );

    const headers = {
      Referer: this.walletUri,
      'Upgrade-Insecure-Requests': '1',
      'Sec-Fetch-Dest': 'document',
      'Sec-Fetch-Mode': 'navigate',
      'Sec-Fetch-Site': 'cross-site',
      'Sec-Fetch-User': '?1',
    };

    return this.httpClient.get.redirect(url, headers, (_, location) => {
      const state = location.split('state=')[1];
      return { location, state };
    });
  };

  // Step through the /login endpoint
  private _login = (location: string, state: string, username: string, password: string) => {
    console.log('Calling login...');

    const data = encodeURI(
      `state=${state}&username=${username}&password=${password}&action=default`,
    );

    const headers = {
      'Content-Type': 'application/x-www-form-urlencoded',
      Origin: this.auth0Tenant,
      Referer: location,
      'Upgrade-Insecure-Requests': '1',
      'Sec-Fetch-Dest': 'document',
      'Sec-Fetch-Mode': 'navigate',
      'Sec-Fetch-Site': 'same-origin',
      'Sec-Fetch-User': '?1',
    };

    return this.httpClient.post.redirect(
      `${this.auth0Tenant}${location}`,
      data,
      headers,
      (_, loc) => {
        const state = loc.split('state=')[1];
        return { location: loc, state };
      },
    );
  };

  // Step through the /authorize/resume endpoint
  private _resume = (location: string) => {
    console.log('Calling resume...');

    const headers = {
      'Content-Type': 'application/x-www-form-urlencoded',
      Origin: this.auth0Tenant,
      Referer: location,
      'Upgrade-Insecure-Requests': '1',
      'Sec-Fetch-Dest': 'document',
      'Sec-Fetch-Mode': 'navigate',
      'Sec-Fetch-Site': 'same-origin',
      'Sec-Fetch-User': '?1',
    };

    return this.httpClient.get.redirect(
      `${this.auth0Tenant}${location}`,
      headers,
      (_, location) => {
        const querystr = location.split('/?')[1];
        const { state, code } = parse(querystr);

        if (!state || !code || Array.isArray(code) || Array.isArray(state)) {
          throw new Error('expected single string state and code params');
        }

        return { location, state, code };
      },
    );
  };

  // Step through the /oauth/token endpoint
  private _token = (code: string): string => {
    const data = encodeURI(
      `grant_type=authorization_code&redirect_uri=${this.walletUri}&code=${code}&code_verifier=${this.codeVerifier}&client_id=${this.clientId}`,
    );

    const headers = {
      'Content-Type': 'application/x-www-form-urlencoded',
      Origin: this.walletUri,
      Referer: this.walletUri,
      'Sec-Fetch-Dest': 'empty',
      'Sec-Fetch-Mode': 'cors',
      'Sec-Fetch-Site': 'cross-site',
    };

    return this.httpClient.post.success(`${this.auth0Tenant}/oauth/token`, data, headers, resp => {
      const json = JSON.parse(resp.body || '{}');

      if (json && typeof json.access_token === 'string') {
        return json.access_token;
      } else {
        throw new Error('Access token not found in response');
      }
    });
  };

  private _parse_credentials = (credentials: string): { username: string; password: string } => {
    const pair = credentials.split(':');
    if (pair.length !== 2) {
      throw new Error(`Failed to parse credentials: ${credentials}`);
    }

    return { username: pair[0], password: pair[1] };
  };

  /**
   *
   * @param credentials : A string "username:password"
   * @returns
   */
  public authorizationCodeGrant = (credentials: string): string => {
    const { username, password } = this._parse_credentials(credentials);

    const a = this._authorize();
    const l = this._login(a.location, a.state, username, password);
    const r = this._resume(l.location);

    return this._token(r.code);
  };
}
