import { sleep } from 'k6';
import http, { RefinedResponse } from 'k6/http';

// HTTP client built off of k6/http, that automatically handles retries
export class HttpClient {
  private retryCount: number = 5;
  private retryWait: number = 1; // in seconds
  private errorsAreFatal: boolean = false;

  private tag: string | undefined;

  constructor(tag?: string, errorsAreFatal?: boolean) {
    this.tag = tag;
    this.errorsAreFatal = !!errorsAreFatal;
  }

  private handleError(
    errorMessage: string,
    debugInfo: object | string | number | boolean | null | undefined,
  ): void {
    console.error(`${errorMessage}. Debug: ${JSON.stringify(debugInfo)}}`);
    if (this.errorsAreFatal) {
      throw new Error(errorMessage);
    }
  }

  // base HTTP request with simple error handling
  private _request<R>(
    url: string,
    method: 'GET' | 'POST',
    body: string | Buffer | undefined,
    expectedStatus: 200 | 201 | 302,
    additionalHeaders: Record<string, string> | undefined,
    retries: number,
    handleResponse: (resp: RefinedResponse<'text'>) => R,
  ): R {
    console.debug(`Calling ${method} on endpoint: ${url}`);

    const headers = {
      ...additionalHeaders,
    };

    const tags = this.tag ? { name: this.tag } : undefined;

    console.debug(`Request data: ${JSON.stringify({ headers, body, url })}`);

    const resp = http.request(method, url, body, {
      headers,
      tags,
      redirects: 0,
    });

    if (resp.status !== expectedStatus) {
      if (retries > 0) {
        console.debug(`Received an unexpected status code ${resp.status} for ${method} ${url}`);
        console.debug(`${retries} retries remaining...`);

        sleep(this.retryWait);
        this._request(
          url,
          method,
          body,
          expectedStatus,
          additionalHeaders,
          retries - 1,
          handleResponse,
        );
      } else {
        this.handleError(
          `Expected status code ${expectedStatus} but received ${resp.status} for ${method} ${url}`,
          resp.body,
        );
      }
    }

    return handleResponse(resp);
  }

  // an HTTP request that is expected to return 302 redirect
  private _redirect<R>(
    url: string,
    method: 'GET' | 'POST',
    body: string | Buffer | undefined,
    additionalHeaders: Record<string, string> | undefined,
    handleResponse: (resp: RefinedResponse<'text'>, location: string) => R,
  ): R {
    return this._request(url, method, body, 302, additionalHeaders, this.retryCount, resp => {
      const location = resp.headers['Location'];

      if (typeof location === 'string') {
        return handleResponse(resp, location);
      } else {
        this.handleError(`Found a 302 but did not find a redirect location for ${url}.`, {
          headers: resp.headers,
          body: resp.body,
        });
        return handleResponse(resp, '');
      }
    });
  }

  public get = {
    redirect: <R>(
      url: string,
      additionalHeaders: Record<string, string>,
      handleResponse: (resp: RefinedResponse<'text'>, location: string) => R,
    ): R => {
      return this._redirect(url, 'GET', undefined, additionalHeaders, handleResponse);
    },
    success: <R>(
      url: string,
      body: string | Buffer | undefined,
      additionalHeaders: Record<string, string>,
      handleResponse: (resp: RefinedResponse<'text'>) => R,
    ): R => {
      return this._request(
        url,
        'GET',
        body,
        200,
        additionalHeaders,
        this.retryCount,
        handleResponse,
      );
    },
  };

  public post = {
    redirect: <R>(
      url: string,
      body: string | Buffer | undefined,
      additionalHeaders: Record<string, string>,
      handleResponse: (resp: RefinedResponse<'text'>, location: string) => R,
    ): R => {
      return this._redirect(url, 'POST', body, additionalHeaders, handleResponse);
    },
    success: <R>(
      url: string,
      body: string | Buffer | undefined,
      additionalHeaders: Record<string, string>,
      handleResponse: (resp: RefinedResponse<'text'>) => R,
    ): R => {
      return this._request(
        url,
        'POST',
        body,
        200,
        additionalHeaders,
        this.retryCount,
        handleResponse,
      );
    },
    s201: <R>(
      url: string,
      body: string | Buffer | undefined,
      additionalHeaders: Record<string, string>,
      handleResponse: (resp: RefinedResponse<'text'>) => R,
    ): R => {
      return this._request(
        url,
        'POST',
        body,
        201,
        additionalHeaders,
        this.retryCount,
        handleResponse,
      );
    },
  };
}
