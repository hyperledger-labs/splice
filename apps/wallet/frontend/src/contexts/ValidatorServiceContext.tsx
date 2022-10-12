import React, { useContext } from 'react';

import { ValidatorAppServiceClient } from '../com/daml/network/validator/v0/Validator_serviceServiceClientPb';

const ValidatorContext = React.createContext<ValidatorAppServiceClient | undefined>(undefined);

export interface ValidatorProps {
  url: string;
}

export const ValidatorClientProvider: React.FC<React.PropsWithChildren<ValidatorProps>> = ({
  url,
  children,
}) => {
  const validatorClient = new ValidatorAppServiceClient(url, null, null);
  return <ValidatorContext.Provider value={validatorClient}>{children}</ValidatorContext.Provider>;
};

export const useValidatorClient: () => ValidatorAppServiceClient = () => {
  const client = useContext<ValidatorAppServiceClient | undefined>(ValidatorContext);
  if (!client) {
    throw new Error('Validator client not initialized');
  }
  return client;
};
