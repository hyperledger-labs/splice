import { UseMutationResult, useMutation } from '@tanstack/react-query';

import { useAppManagerClient } from '../../contexts/AppManagerServiceContext';

export type AuthorizeArguments = {
  redirectUri: string;
  state: string;
  userId: string;
};
export const useAuthorize = (): UseMutationResult<string, unknown, AuthorizeArguments, unknown> => {
  const appManagerClient = useAppManagerClient();
  return useMutation({
    mutationFn: async ({ redirectUri, state, userId }): Promise<string> => {
      return (await appManagerClient.authorize(redirectUri!, state!, userId!)).redirectUri;
    },
  });
};
