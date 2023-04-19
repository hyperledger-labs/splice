import { OpenAPILoggingMiddleware, useUserState } from 'common-frontend';
import React, { useContext, useMemo } from 'react';
import {
  Middleware,
  createConfiguration,
  ValidatorApi,
  ServerConfiguration,
  RequestContext,
  ResponseContext,
} from 'validator-openapi';

const ValidatorContext = React.createContext<ValidatorClient | undefined>(undefined);

export interface ValidatorProps {
  url: string;
}

export interface ValidatorClient {
  registerUser: () => Promise<void>;
}

class ApiMiddleware implements Middleware {
  private token: string | undefined;

  async pre(context: RequestContext): Promise<RequestContext> {
    if (!this.token) {
      throw new Error('Request issued before access token was set');
    }
    context.setHeaderParam('Authorization', `Bearer ${this.token}`);
    return context;
  }
  post(context: ResponseContext): Promise<ResponseContext> {
    return Promise.resolve(context);
  }
  constructor(accessToken: string | undefined) {
    this.token = accessToken;
  }
}

export const ValidatorClientProvider: React.FC<React.PropsWithChildren<ValidatorProps>> = ({
  url,
  children,
}) => {
  const { userAccessToken } = useUserState();

  const friendlyClient: ValidatorClient | undefined = useMemo(() => {
    const configuration = createConfiguration({
      baseServer: new ServerConfiguration(url, {}),
      promiseMiddleware: [
        new ApiMiddleware(userAccessToken),
        new OpenAPILoggingMiddleware('validator'),
      ],
    });

    const validatorClient = new ValidatorApi(configuration);

    return {
      registerUser: async (): Promise<void> => {
        validatorClient.register();
      },
    };
  }, [url, userAccessToken]);

  return <ValidatorContext.Provider value={friendlyClient}>{children}</ValidatorContext.Provider>;
};

export const useValidatorClient: () => ValidatorClient = () => {
  const client = useContext<ValidatorClient | undefined>(ValidatorContext);
  if (!client) {
    throw new Error('Validator client not initialized');
  }
  return client;
};
