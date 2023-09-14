import { UseMutationResult, useMutation } from '@tanstack/react-query';

import { useAppManagerAdminClient } from '../../contexts/AppManagerServiceContext';

export const useInstallApp = (): UseMutationResult<void, unknown, string, unknown> => {
  const appManagerClient = useAppManagerAdminClient();
  return useMutation({
    mutationFn: async (app_url: string) => {
      await appManagerClient.installApp({ app_url });
    },
  });
};
