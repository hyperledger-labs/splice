import { useMutation, UseMutationResult, useQueryClient } from '@tanstack/react-query';

import { useExternalDirectoryClient } from '../../context/ValidatorServiceContext';
import { QueryDirectoryInstallOperationName } from '../queries/useDirectoryInstall';

const useRequestDirectoryInstall: () => UseMutationResult<void, unknown, unknown> = () => {
  const queryClient = useQueryClient();
  const directoryApi = useExternalDirectoryClient();

  return useMutation({
    mutationFn: async () => {
      console.debug('Creating DirectoryInstallRequest');
      await directoryApi.createDirectoryInstall();
      console.debug('Created DirectoryInstallRequest');
    },
    onError: (error: unknown) => {
      console.error('Failed to setup install contract: ', error);
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: [QueryDirectoryInstallOperationName] });
    },
    retry: 3,
    retryDelay: 500,
  });
};

export default useRequestDirectoryInstall;
