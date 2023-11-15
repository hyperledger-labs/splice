import http, { RefinedResponse } from 'k6/http';

export class HttpClient {
  // we're _definitely_ a browser ;)
  private headers: Record<string, string> = {
    'User-Agent':
      'Mozilla/5.0 (Macintosh; Intel Mac OS X 10.15; rv:109.0) Gecko/20100101 Firefox/119.0',
    Accept: 'text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8',
    'Accept-Language': 'en-US,en;q=0.5',
    Connection: 'keep-alive',
  };

  private tag: string | undefined;

  constructor(tag: string) {
    this.tag = tag;
  }

  // base HTTP request with simple error handling
  private _request<R>(
    url: string,
    method: 'GET' | 'POST',
    body: string | Buffer | undefined,
    expectedStatus: 200 | 302,
    additionalHeaders: Record<string, string>,
    handleResponse: (resp: RefinedResponse<'text'>) => R,
  ): R {
    console.log(`Calling ${method} on endpoint: ${url}`);

    const headers = {
      ...this.headers,
      ...additionalHeaders,
    };

    const tags = this.tag ? { name: this.tag } : undefined;

    const resp = http.request(method, url, body, {
      headers,
      tags,
      redirects: 0,
    });

    if (resp.status !== expectedStatus) {
      console.error(resp.headers, resp.body);
      throw new Error(
        `Expected status code ${expectedStatus} but received ${resp.status} for ${method} ${url}`,
      );
    }

    return handleResponse(resp);
  }

  // an HTTP request that is expected to return 302 redirect
  private _redirect<R>(
    url: string,
    method: 'GET' | 'POST',
    body: string | Buffer | undefined,
    additionalHeaders: Record<string, string>,
    handleResponse: (resp: RefinedResponse<'text'>, location: string) => R,
  ): R {
    return this._request(url, method, body, 302, additionalHeaders, resp => {
      const location = resp.headers['Location'];

      if (typeof location === 'string') {
        return handleResponse(resp, location);
      } else {
        console.error(resp.headers);
        throw new Error(`Found a 302 but did not find a redirect location for ${url}.`);
      }
    });
  }

  public getRedirect<R>(
    url: string,
    additionalHeaders: Record<string, string>,
    handleResponse: (resp: RefinedResponse<'text'>, location: string) => R,
  ): R {
    return this._redirect(url, 'GET', undefined, additionalHeaders, handleResponse);
  }

  public postRedirect<R>(
    url: string,
    body: string | Buffer | undefined,
    additionalHeaders: Record<string, string>,
    handleResponse: (resp: RefinedResponse<'text'>, location: string) => R,
  ): R {
    return this._redirect(url, 'POST', body, additionalHeaders, handleResponse);
  }

  public postSuccess<R>(
    url: string,
    body: string | Buffer | undefined,
    additionalHeaders: Record<string, string>,
    handleResponse: (resp: RefinedResponse<'text'>) => R,
  ): R {
    return this._request(url, 'POST', body, 200, additionalHeaders, handleResponse);
  }
}
