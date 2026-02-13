// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { sleep } from 'k6';
import http, { RefinedParams, RefinedResponse, ResponseType } from 'k6/http';

type ResponseHandler = {
  then: <T>(handleResponse: (n: RefinedResponse<'text'>) => T) => T;
};

type RedirectResponseHandler = {
  then: <T>(handleResponse: (n: RefinedResponse<'text'>, location: string) => T) => T;
};

interface RequestParams<R extends ResponseType> extends RefinedParams<R | undefined> {
  retry?: number | boolean | RetryFn;
}

type RetryFn = (count: number, resp?: RefinedResponse<'text'>) => boolean;

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

  private _raw_request<R extends ResponseType>(
    url: string,
    method: 'GET' | 'POST',
    body: string | Buffer | undefined,
    params: RequestParams<R>,
  ): RefinedResponse<'text'> {
    console.debug(`Calling ${method} on endpoint: ${url}`);

    const headers = {
      ...params?.headers,
    };

    const tags = {
      ...params?.tags,
      ...(this.tag ? { name: this.tag } : undefined),
    };

    const resp = http.request(method, url, body, {
      headers,
      tags,
      redirects: 0,
    });


  // base HTTP request with simple error handling
  private _request<R extends ResponseType>(
    url: string,
    method: 'GET' | 'POST',
    body: string | Buffer | undefined,
    expectedStatus: 200 | 201 | 302,
    params: RequestParams<R>,
  ): ResponseHandler {
    const { retry = true } = params;

    const hardRetryTimeout = 30; // in seconds

    // default retry values
    let maxRetryCount = this.retryCount;
    let retryCount = 0;
    let retryCondition: RetryFn = (count: number) => count < maxRetryCount;

    switch (typeof retry) {
      case 'boolean':
        if (!params.retry) {
          retryCondition = () => false;
        }
        break;
      case 'number':
        maxRetryCount = retry;
        break;
      case 'function':
        retryCondition = retry;
        break;
    }

    let resp = this._raw_request(url, method, body, params);

    const startTime = Date.now();
    while (resp.status !== expectedStatus) {
      if (Date.now() - startTime > hardRetryTimeout * 1000) {
        this.handleError(
          `Exceeded max retry time of ${hardRetryTimeout} seconds: ${method} ${url}`,
          resp.body,
        );
        break;
      }

      if (retryCondition(retryCount, resp)) {
        console.log(`Retrying, count #${retryCount}...`);
        sleep(this.retryWait);
        resp = this._raw_request(url, method, body, params);
        retryCount++;
      } else {
        this.handleError(
          `Expected status code ${expectedStatus} but received ${resp.status} for ${method} ${url}`,
          resp.body,
        );
        break;
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
    params: RequestParams<R>,
  ): RedirectResponseHandler {
    return this._request(url, method, body, 302, params).then(resp => {
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
      params: RequestParams<R>,
    ): RedirectResponseHandler => {
      return this._redirect(url, 'GET', undefined, params);
    },
    success: <R extends ResponseType>(
      url: string,
      body: string | Buffer | undefined,
      params: RequestParams<R>,
    ): ResponseHandler => {
      return this._request(url, 'GET', body, 200, params);
    },
  };

  public post = {
    redirect: <R extends ResponseType>(
      url: string,
      body: string | Buffer | undefined,
      params: RequestParams<R>,
    ): RedirectResponseHandler => {
      return this._redirect(url, 'POST', body, params);
    },
    success: <R extends ResponseType>(
      url: string,
      body: string | Buffer | undefined,
      params: RequestParams<R>,
    ): ResponseHandler => {
      return this._request(url, 'POST', body, 200, params);
    },
    s201: <R extends ResponseType>(
      url: string,
      body: string | Buffer | undefined,
      params: RequestParams<R>,
    ): ResponseHandler => {
      return this._request(url, 'POST', body, 201, params);
    },
  };
}
