import { UseMutationResult, useMutation } from '@tanstack/react-query';
import { ApproveAppReleaseConfigurationRequest } from 'validator-openapi';

import { useAppManagerAdminClient } from '../../contexts/AppManagerServiceContext';

type Request = ApproveAppReleaseConfigurationRequest & {
  provider: string;
};

export const useApproveAppReleaseConfiguration = (): UseMutationResult<
  void,
  unknown,
  Request,
  unknown
> => {
  const appManagerClient = useAppManagerAdminClient();
  return useMutation({
    mutationFn: async ({ provider, configuration_version, release_configuration_index }) => {
      await appManagerClient.approveAppReleaseConfiguration(provider, {
        configuration_version,
        release_configuration_index,
      });
    },
  });
};
