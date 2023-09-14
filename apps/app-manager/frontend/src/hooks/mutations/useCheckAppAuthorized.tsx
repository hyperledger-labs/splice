import { UseMutationResult, useMutation } from '@tanstack/react-query';

import { useAppManagerClient } from '../../contexts/AppManagerServiceContext';

export type AuthorizeArguments = {
  provider: string;
  redirectUri: string;
  state: string;
};
export const useCheckAppAuthorized = (): UseMutationResult<
  string,
  unknown,
  AuthorizeArguments,
  unknown
> => {
  const appManagerClient = useAppManagerClient();
  return useMutation({
    mutationFn: async ({ provider, redirectUri, state }): Promise<string> => {
      return (await appManagerClient.checkAppAuthorized(provider, redirectUri!, state!))
        .redirect_uri;
    },
  });
};
