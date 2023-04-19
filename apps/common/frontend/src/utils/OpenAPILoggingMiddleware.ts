// These interfaces have the same shape as the ones in openapi-ts-client
interface LoggableRequestContext {
  getUrl(): string;
  getHttpMethod(): string;
  getBody(): unknown;
}
interface LoggableResponseContext {
  httpStatusCode: number;
  getBodyAsAny(): Promise<string | Blob | undefined>;
}

const MAX_BODY_LENGTH_TO_LOG = 300;
export class OpenAPILoggingMiddleware<
  RequestContext extends LoggableRequestContext,
  ResponseContext extends LoggableResponseContext
> {
  readonly name: string;
  public constructor(name: string) {
    this.name = name;
  }

  async pre(context: RequestContext): Promise<RequestContext> {
    const body = context.getBody();
    console.debug(
      `${this.name} calling`,
      context.getHttpMethod(),
      context.getUrl(),
      'with body:',
      body && JSON.stringify(body).substring(0, MAX_BODY_LENGTH_TO_LOG)
    );
    return context;
  }
  async post(context: ResponseContext): Promise<ResponseContext> {
    // Unfortunately we don't have the URL
    return context.getBodyAsAny().then(body => {
      console.debug(
        `${this.name} got response with status code`,
        context.httpStatusCode,
        'and body:',
        body && JSON.stringify(body).substring(0, MAX_BODY_LENGTH_TO_LOG)
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
    });
  }
}
