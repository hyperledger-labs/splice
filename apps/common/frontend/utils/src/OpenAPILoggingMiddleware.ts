// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

// These interfaces have the same shape as the ones in openapi-ts-client

interface LoggableRequestContext {
  getUrl(): string;
  getHttpMethod(): string;
  getBody(): unknown;
  getHeaders(): { [key: string]: string };
  setHeaderParam(key: string, value: string): void;
}
interface LoggableResponseContext {
  httpStatusCode: number;
  headers: {
    [key: string]: string;
  };
  getBodyAsAny(): Promise<string | Blob | undefined>;
  getParsedHeader(headerName: string): { [parameter: string]: string };
}
/** Returns a random array of `length` bytes, encoded as a hex string. */
const randomHexString = (length: number) =>
  Array.from(crypto.getRandomValues(new Uint8Array(length)))
    .map(b => b.toString(16).padStart(2, '0'))
    .join('');

const MAX_BODY_LENGTH_TO_LOG = 300;
export class OpenAPILoggingMiddleware<
  RequestContext extends LoggableRequestContext,
  ResponseContext extends LoggableResponseContext,
> {
  readonly name: string;
  public constructor(name: string) {
    this.name = name;
  }

  async pre(context: RequestContext): Promise<RequestContext> {
    const traceId = randomHexString(16);
    const parentId = randomHexString(8);
    const traceparent = `00-${traceId}-${parentId}-01`;
    const body = context.getBody();

    console.debug(
      `${this.name} calling`,
      context.getHttpMethod(),
      context.getUrl(),
      'with body:',
      body && JSON.stringify(body).substring(0, MAX_BODY_LENGTH_TO_LOG),
      `[${traceparent}]`
    );
    context.setHeaderParam('traceparent', traceparent);
    return context;
  }
  async post(context: ResponseContext): Promise<ResponseContext> {
    const traceparent = context.headers['traceparent'];

    // Unfortunately we don't have the URL
    return context.getBodyAsAny().then(
      body => {
        console.debug(
          `${this.name} got response with status code`,
          context.httpStatusCode,
          'and body:',
          body && JSON.stringify(body).substring(0, MAX_BODY_LENGTH_TO_LOG),
          `[${traceparent}]`
        );
        // Work-around for `TypeError: Response.text: Body has already been consumed.`
        return {
          ...context,
          getBodyAsAny: () => Promise.resolve(body),
          body: {
            text: () => {
              if (typeof body === 'string') {
                return Promise.resolve(body);
              } else {
                return Promise.reject('Body is not a string.');
              }
            },
            binary: () => {
              if (typeof body === 'string') {
                return Promise.resolve(new Blob([body]));
              } else {
                return Promise.resolve(body);
              }
            },
          },
        };
      },
      err => {
        console.error(
          `Got an error when getting response body (traceparent: [${traceparent}]:`,
          err
        );
        throw err;
      }
    );
  }
}
