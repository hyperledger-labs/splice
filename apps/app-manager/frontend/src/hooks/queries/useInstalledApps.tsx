import { UseQueryResult, useQuery } from '@tanstack/react-query';
import { InstalledApp } from 'validator-openapi';

import { useAppManagerClient } from '../../contexts/AppManagerServiceContext';

export const useInstalledApps: () => UseQueryResult<InstalledApp[]> = () => {
  const appManagerClient = useAppManagerClient();
  return useQuery({
    queryFn: async () => {
      return (await appManagerClient.listInstalledApps()).apps;
    },
    queryKey: ['getInstalledApps'],
  });
};
