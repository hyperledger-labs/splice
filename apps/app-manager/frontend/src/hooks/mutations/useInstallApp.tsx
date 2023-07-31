import { UseMutationResult, useMutation } from '@tanstack/react-query';

import { useAppManagerClient } from '../../contexts/AppManagerServiceContext';

export const useInstallApp = (): UseMutationResult<void, unknown, string, unknown> => {
  const appManagerClient = useAppManagerClient();
  return useMutation({
    mutationFn: async (appUrl: string) => {
      await appManagerClient.installApp({ appUrl });
    },
  });
};
