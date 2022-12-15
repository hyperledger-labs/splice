import * as v0 from 'common-protobuf/com/daml/network/validator/v0/validator_service_pb';
import { useUserState } from 'common-frontend';
import { ValidatorAppServicePromiseClient } from 'common-protobuf/com/daml/network/validator/v0/validator_service_grpc_web_pb';
import { Metadata } from 'grpc-web';
import React, { useContext, useMemo } from 'react';

const ValidatorContext = React.createContext<ValidatorClient | undefined>(undefined);

export interface ValidatorProps {
  url: string;
}

export interface OnboardUserRequest {
  userId: string;
}

export interface OnboardUserResponse {}

export interface ValidatorClient {
  onboardUser: (userId: string) => Promise<void>;
}

interface Credentials extends Metadata {
  Authorization: string;
}

export const ValidatorClientProvider: React.FC<React.PropsWithChildren<ValidatorProps>> = ({
  url,
  children,
}) => {
  const { userAccessToken } = useUserState();

  const friendlyClient: ValidatorClient | undefined = useMemo(() => {
    const getCreds = (): Credentials => {
      if (!userAccessToken) {
        throw new Error('Request issued before access token was set');
      }
      return {
        Authorization: `Bearer ${userAccessToken}`,
      };
    };

    const validatorClient = new ValidatorAppServicePromiseClient(url, null, null);

    return {
      onboardUser: async (userId: string): Promise<void> => {
        validatorClient.onboardUser(new v0.OnboardUserRequest().setName(userId), getCreds());
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
