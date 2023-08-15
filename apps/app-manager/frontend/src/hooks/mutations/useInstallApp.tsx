import { UseMutationResult, useMutation } from '@tanstack/react-query';

import { useAppManagerAdminClient } from '../../contexts/AppManagerServiceContext';

export const useInstallApp = (): UseMutationResult<void, unknown, string, unknown> => {
  const appManagerClient = useAppManagerAdminClient();
  return useMutation({
    mutationFn: async (appUrl: string) => {
      await appManagerClient.installApp({ appUrl });
    },
  });
};
