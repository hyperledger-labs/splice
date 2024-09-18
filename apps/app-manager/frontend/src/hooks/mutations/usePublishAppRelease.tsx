import { UseMutationResult, useMutation } from '@tanstack/react-query';
import { HttpFile } from 'validator-openapi';

import { useAppManagerAdminClient } from '../../contexts/AppManagerServiceContext';

export type PublishAppReleaseRequest = {
  provider: string;
  release: HttpFile;
};

export const usePublishAppRelease = (): UseMutationResult<
  void,
  unknown,
  PublishAppReleaseRequest,
  unknown
> => {
  const appManagerClient = useAppManagerAdminClient();
  return useMutation({
    mutationFn: async ({ provider, release }) => {
      await appManagerClient.publishAppRelease(provider, release);
    },
  });
};
