import { UseMutationResult, useMutation } from '@tanstack/react-query';
import { HttpFile } from 'validator-openapi';

import { useAppManagerClient } from '../../contexts/AppManagerServiceContext';

export const useRegisterApp = (): UseMutationResult<void, unknown, HttpFile, unknown> => {
  const appManagerClient = useAppManagerClient();
  return useMutation({
    mutationFn: async appBundle => {
      await appManagerClient.registerApp(appBundle);
    },
  });
};
