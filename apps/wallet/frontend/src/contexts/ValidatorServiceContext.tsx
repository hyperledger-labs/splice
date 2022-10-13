import React, { useContext } from 'react';

import { ValidatorAppServicePromiseClient } from '../com/daml/network/validator/v0/validator_service_grpc_web_pb';

const ValidatorContext = React.createContext<ValidatorAppServicePromiseClient | undefined>(
  undefined
);

export interface ValidatorProps {
  url: string;
}

export const ValidatorClientProvider: React.FC<React.PropsWithChildren<ValidatorProps>> = ({
  url,
  children,
}) => {
  const validatorClient = new ValidatorAppServicePromiseClient(url, null, null);
  return <ValidatorContext.Provider value={validatorClient}>{children}</ValidatorContext.Provider>;
};

export const useValidatorClient: () => ValidatorAppServicePromiseClient = () => {
  const client = useContext<ValidatorAppServicePromiseClient | undefined>(ValidatorContext);
  if (!client) {
    throw new Error('Validator client not initialized');
  }
  return client;
};
