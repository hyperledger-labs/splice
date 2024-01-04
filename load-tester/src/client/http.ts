import { sleep } from 'k6';
import http, { RefinedParams, RefinedResponse, ResponseType } from 'k6/http';

type ResponseHandler = {
  then: <T>(handleResponse: (n: RefinedResponse<'text'>) => T) => T;
};

type RedirectResponseHandler = {
  then: <T>(handleResponse: (n: RefinedResponse<'text'>, location: string) => T) => T;
};

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
  private _request<R extends ResponseType>(
    url: string,
    method: 'GET' | 'POST',
    body: string | Buffer | undefined,
    expectedStatus: 200 | 201 | 302,
    retries: number,
    params: RefinedParams<R | undefined> | null | undefined,
  ): ResponseHandler {
    console.debug(`Calling ${method} on endpoint: ${url}`);

    const headers = {
      ...params?.headers,
    };

    const tags = {
      ...params?.tags,
      ...(this.tag ? { name: this.tag } : undefined),
    };

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
        return this._request(url, method, body, expectedStatus, retries - 1, params);
      } else {
        this.handleError(
          `Expected status code ${expectedStatus} but received ${resp.status} for ${method} ${url}`,
          resp.body,
        );
      }
    }

    return {
      then: function <T>(handleResponse: (resp: RefinedResponse<'text'>) => T) {
        return handleResponse(resp);
      },
    };
  }

  // an HTTP request that is expected to return 302 redirect
  private _redirect<R extends ResponseType>(
    url: string,
    method: 'GET' | 'POST',
    body: string | Buffer | undefined,
    params: RefinedParams<R | undefined> | null | undefined,
  ): RedirectResponseHandler {
    return this._request(url, method, body, 302, this.retryCount, params).then(resp => {
      const location = resp.headers['Location'];

      if (typeof location !== 'string') {
        this.handleError(`Found a 302 but did not find a redirect location for ${url}.`, {
          headers: resp.headers,
          body: resp.body,
        });
      }

      return {
        then: function <T>(
          handleResponse: (_resp: RefinedResponse<'text'>, _location: string) => T,
        ) {
          return handleResponse(resp, location || '');
        },
      };
    });
  }

  public get = {
    redirect: <R extends ResponseType>(
      url: string,
      params: RefinedParams<R | undefined> | null | undefined,
    ): RedirectResponseHandler => {
      return this._redirect(url, 'GET', undefined, params);
    },
    success: <R extends ResponseType>(
      url: string,
      body: string | Buffer | undefined,
      params: RefinedParams<R | undefined> | null | undefined,
    ): ResponseHandler => {
      return this._request(url, 'GET', body, 200, this.retryCount, params);
    },
  };

  public post = {
    redirect: <R extends ResponseType>(
      url: string,
      body: string | Buffer | undefined,
      params: RefinedParams<R | undefined> | null | undefined,
    ): RedirectResponseHandler => {
      return this._redirect(url, 'POST', body, params);
    },
    success: <R extends ResponseType>(
      url: string,
      body: string | Buffer | undefined,
      params: RefinedParams<R | undefined> | null | undefined,
    ): ResponseHandler => {
      return this._request(url, 'POST', body, 200, this.retryCount, params);
    },
    s201: <R extends ResponseType>(
      url: string,
      body: string | Buffer | undefined,
      params: RefinedParams<R | undefined> | null | undefined,
    ): ResponseHandler => {
      return this._request(url, 'POST', body, 201, this.retryCount, params);
    },
  };
}
