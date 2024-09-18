import { UseMutationResult, useMutation } from '@tanstack/react-query';
import { AppConfiguration } from 'validator-openapi';

import { useAppManagerAdminClient } from '../../contexts/AppManagerServiceContext';

export type UpdateAppConfigurationRequest = {
  provider: string;
  configuration: AppConfiguration;
};

export const useUpdateAppConfiguration = (): UseMutationResult<
  void,
  unknown,
  UpdateAppConfigurationRequest,
  unknown
> => {
  const appManagerClient = useAppManagerAdminClient();
  return useMutation({
    mutationFn: async ({ provider, configuration }) => {
      await appManagerClient.updateAppConfiguration(provider, { configuration });
    },
  });
};
