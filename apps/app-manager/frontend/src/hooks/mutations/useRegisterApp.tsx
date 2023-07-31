import { UseMutationResult, useMutation } from '@tanstack/react-query';
import { useUserState } from 'common-frontend';
import { Domain, HttpFile } from 'validator-openapi';

import { useAppManagerClient } from '../../contexts/AppManagerServiceContext';

export type RegisterAppRequest = {
  name: string;
  uiUrl: string;
  domains: Domain[];
  release: HttpFile;
};

export const useRegisterApp = (): UseMutationResult<void, unknown, RegisterAppRequest, unknown> => {
  const userId = useUserState().userId!;
  const appManagerClient = useAppManagerClient();
  return useMutation({
    mutationFn: async ({ name, uiUrl, domains, release }) => {
      await appManagerClient.registerApp(userId, name, uiUrl, JSON.stringify(domains), release);
    },
  });
};
