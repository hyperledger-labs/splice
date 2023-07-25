import { UseQueryResult, useQuery } from '@tanstack/react-query';
import { RegisteredApp } from 'validator-openapi';

import { useAppManagerClient } from '../../contexts/AppManagerServiceContext';

export const useRegisteredApps: () => UseQueryResult<RegisteredApp[]> = () => {
  const appManagerClient = useAppManagerClient();
  return useQuery({
    queryFn: async () => {
      return (await appManagerClient.listRegisteredApps()).apps;
    },
    queryKey: ['getRegisteredApps'],
  });
};
