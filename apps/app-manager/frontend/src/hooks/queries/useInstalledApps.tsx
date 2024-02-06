import { UseQueryResult, useQuery } from '@tanstack/react-query';
import { PollingStrategy } from 'common-frontend-utils';
import { InstalledApp } from 'validator-openapi';

import { useAppManagerClient } from '../../contexts/AppManagerServiceContext';

export const useInstalledApps: () => UseQueryResult<InstalledApp[]> = () => {
  const appManagerClient = useAppManagerClient();
  return useQuery({
    refetchInterval: PollingStrategy.FIXED,
    queryFn: async () => {
      return (await appManagerClient.listInstalledApps()).apps;
    },
    queryKey: ['getInstalledApps'],
  });
};
