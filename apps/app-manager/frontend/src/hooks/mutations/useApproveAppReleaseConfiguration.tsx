import { UseMutationResult, useMutation } from '@tanstack/react-query';

import { useAppManagerClient } from '../../contexts/AppManagerServiceContext';

export type ApproveAppReleaseConfigurationRequest = {
  provider: string;
  configurationVersion: number;
  releaseConfigurationIndex: number;
};

export const useApproveAppReleaseConfiguration = (): UseMutationResult<
  void,
  unknown,
  ApproveAppReleaseConfigurationRequest,
  unknown
> => {
  const appManagerClient = useAppManagerClient();
  return useMutation({
    mutationFn: async ({ provider, configurationVersion, releaseConfigurationIndex }) => {
      await appManagerClient.approveAppReleaseConfiguration(provider, {
        configurationVersion,
        releaseConfigurationIndex,
      });
    },
  });
};
