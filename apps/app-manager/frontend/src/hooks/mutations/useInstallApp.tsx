import { UseMutationResult, useMutation } from '@tanstack/react-query';

import { useAppManagerClient } from '../../contexts/AppManagerServiceContext';

export const useInstallApp = (): UseMutationResult<void, unknown, string, unknown> => {
  const appManagerClient = useAppManagerClient();
  return useMutation({
    mutationFn: async (manifestUrl: string) => {
      await appManagerClient.installApp({ manifestUrl });
    },
  });
};
