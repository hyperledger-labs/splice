// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { jsonStringDecoder } from '../../utils';
import { HttpClient } from '../http';
import { listUsersResponse } from './models';

function parse(querystr: string): Record<string, string> {
  return querystr.split('&').reduce((prev, current) => {
    const key = current.split('=')[0];
    const val = current.split('=')[1];

    return { ...prev, [key]: val };
  }, {});
}

interface ClientCredentials {
  clientId: string;
  clientSecret: string;
}

type TokenRequest =
  | {
      grantType: 'authorization_code';
      clientId: string;
      code: string;
      codeVerifier: string;
      redirectUri: string;
    }
  | {
      grantType: 'client_credentials';
      clientId: string;
      clientSecret: string;
      audience: string;
    };

// While most of this follows a standard oauth2 authorization code grant,
//  parts of it make specific auth0 assumptions
export class Auth0Manager {
  private auth0Tenant: string;
  private managementApiToken: string;

  private connection: string = 'Username-Password-Authentication';

  private clientId: string;
  private walletUri: string;
  private cnAudience: string = 'https://canton.network.global';

  private codeVerifier: string =
    'acodeverifieracodeverifieracodeverifieracodeverifieracodeverifier';

  private httpClient: HttpClient;
  // we're _definitely_ a browser ;)
  private headers: Record<string, string> = {
    'User-Agent':
      'Mozilla/5.0 (Macintosh; Intel Mac OS X 10.15; rv:109.0) Gecko/20100101 Firefox/119.0',
    Accept: 'text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8',
    'Accept-Language': 'en-US,en;q=0.5',
    Connection: 'keep-alive',
  };

  constructor(
    auth0Tenant: string,
    clientId: string,
    walletUri: string,
    managementApi: ClientCredentials,
  ) {
    this.httpClient = new HttpClient(auth0Tenant, true);
    this.auth0Tenant = auth0Tenant;
    this.clientId = clientId;
    this.walletUri = walletUri;
    this.managementApiToken = this.clientCredentialsGrant(
      managementApi.clientId,
      managementApi.clientSecret,
      `${this.auth0Tenant}/api/v2/`,
    );
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
      ...this.headers,
      Referer: this.walletUri,
      'Upgrade-Insecure-Requests': '1',
      'Sec-Fetch-Dest': 'document',
      'Sec-Fetch-Mode': 'navigate',
      'Sec-Fetch-Site': 'cross-site',
      'Sec-Fetch-User': '?1',
    };

    return this.httpClient.get.redirect(url, { headers }).then((_, location) => {
      const state = location.split('state=')[1];
      return { location, state };
    });
  };

  // Step through the /login endpoint
  private _login = (location: string, state: string, username: string, password: string) => {
    console.log('Calling login...');

    const data = `state=${state}&username=${username}&password=${password}&action=default`;
    const headers = {
      ...this.headers,
      'Content-Type': 'application/x-www-form-urlencoded',
      Origin: this.auth0Tenant,
      Referer: location,
      'Upgrade-Insecure-Requests': '1',
      'Sec-Fetch-Dest': 'document',
      'Sec-Fetch-Mode': 'navigate',
      'Sec-Fetch-Site': 'same-origin',
      'Sec-Fetch-User': '?1',
    };

    return this.httpClient.post
      .redirect(`${this.auth0Tenant}${location}`, data, { headers })
      .then((_, loc) => {
        const state = loc.split('state=')[1];
        return { location: loc, state };
      });
  };

  // Step through the /authorize/resume endpoint
  private _resume = (location: string) => {
    console.log('Calling resume...');

    const headers = {
      ...this.headers,
      'Content-Type': 'application/x-www-form-urlencoded',
      Origin: this.auth0Tenant,
      Referer: location,
      'Upgrade-Insecure-Requests': '1',
      'Sec-Fetch-Dest': 'document',
      'Sec-Fetch-Mode': 'navigate',
      'Sec-Fetch-Site': 'same-origin',
      'Sec-Fetch-User': '?1',
    };

    return this.httpClient.get
      .redirect(`${this.auth0Tenant}${location}`, { headers })
      .then((_, location) => {
        const querystr = location.split('/?')[1];
        const { state, code } = parse(querystr);

        if (!state || !code || Array.isArray(code) || Array.isArray(state)) {
          throw new Error('expected single string state and code params');
        }

        return { location, state, code };
      });
  };

  // Step through the /oauth/token endpoint
  private _token = (req: TokenRequest): string => {
    let data = `grant_type=${req.grantType}&client_id=${req.clientId}`;
    let headers: Record<string, string> = {
      'Content-Type': 'application/x-www-form-urlencoded',
    };

    if (req.grantType === 'authorization_code') {
      data += `&redirect_uri=${req.redirectUri}&code=${req.code}&code_verifier=${this.codeVerifier}`;
      headers = {
        ...this.headers,
        ...headers,
        Origin: req.redirectUri,
        Referer: req.redirectUri,
        'Sec-Fetch-Dest': 'empty',
        'Sec-Fetch-Mode': 'cors',
        'Sec-Fetch-Site': 'cross-site',
      };
    } else if (req.grantType === 'client_credentials') {
      data += `&client_secret=${req.clientSecret}&audience=${req.audience}`;
    }

    return this.httpClient.post
      .success(`${this.auth0Tenant}/oauth/token`, data, { headers })
      .then(resp => {
        const json = JSON.parse(resp.body || '{}');

        if (json && typeof json.access_token === 'string') {
          return json.access_token;
        } else {
          throw new Error('Access token not found in response');
        }
      });
  };

  /**
   *
   * @param credentials : A string "username:password"
   * @returns
   */
  public authorizationCodeGrant = (username: string, password: string): string => {
    const a = this._authorize();
    const l = this._login(a.location, a.state, username, password);
    const r = this._resume(l.location);

    return this._token({
      grantType: 'authorization_code',
      clientId: this.clientId,
      code: r.code,
      codeVerifier: this.codeVerifier,
      redirectUri: this.walletUri,
    });
  };

  public clientCredentialsGrant = (
    clientId: string,
    clientSecret: string,
    audience: string,
  ): string => {
    return this._token({ grantType: 'client_credentials', clientId, clientSecret, audience });
  };

  public userExists = (email: string): boolean => {
    const query = `&search_engine=v3&q=${encodeURIComponent(`email:${email}`)}`;

    const headers = {
      Accept: 'application/json',
      Authorization: `Bearer ${this.managementApiToken}`,
    };

    const response = this.httpClient.get
      .success(`${this.auth0Tenant}/api/v2/users?${query}`, undefined, { headers })
      .then(resp => jsonStringDecoder(listUsersResponse, resp.body));

    return !!response?.find(u => u.email === email);
  };

  public createUser = (email: string, password: string): void => {
    const headers = {
      Accept: 'application/json',
      Authorization: `Bearer ${this.managementApiToken}`,
    };

    this.httpClient.post.s201(
      `${this.auth0Tenant}/api/v2/users`,
      JSON.stringify({ email, password, connection: this.connection }),
      { headers },
    );
  };
}
