import { useUserState } from 'common-frontend';
import React, { useContext, useMemo } from 'react';
import {
  Middleware,
  createConfiguration,
  OnboardUserRequest as HttpOnboardUserRequest,
  ValidatorApi,
  ServerConfiguration,
  RequestContext,
  ResponseContext,
} from 'validator-openapi';

import { BaseApiMiddleware } from '../utils/BaseApiMiddleware';

const ValidatorContext = React.createContext<ValidatorClient | undefined>(undefined);

export interface ValidatorProps {
  url: string;
}
export interface ValidatorClient {
  onboardUser: (userId: string) => Promise<void>;
}

class ApiMiddleware
  extends BaseApiMiddleware<RequestContext, ResponseContext>
  implements Middleware {}

export const ValidatorClientProvider: React.FC<React.PropsWithChildren<ValidatorProps>> = ({
  url,
  children,
}) => {
  const { userAccessToken } = useUserState();

  const friendlyClient: ValidatorClient | undefined = useMemo(() => {
    const configuration = createConfiguration({
      baseServer: new ServerConfiguration(url, {}),
      promiseMiddleware: [new ApiMiddleware(userAccessToken)],
    });

    const validatorClient = new ValidatorApi(configuration);

    return {
      onboardUser: async (userId: string): Promise<void> => {
        const req = new HttpOnboardUserRequest();
        req.name = userId;
        await validatorClient.register();
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
