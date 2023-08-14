import { UseMutationResult, useMutation } from '@tanstack/react-query';
import { useUserState } from 'common-frontend';
import { AppConfiguration, HttpFile, ReleaseConfiguration } from 'validator-openapi';

import { useAppManagerClient } from '../../contexts/AppManagerServiceContext';

export type RegisterAppRequest = {
  configuration: AppConfiguration;
  release: HttpFile;
};

// The generated code doesn't seem to expose the ObjectSerializer which should usually do this so we have to do it ourselves.
const encodeConfiguration = (configuration: AppConfiguration) => ({
  version: configuration.version,
  name: configuration.name,
  ui_url: configuration.uiUrl,
  release_configurations: configuration.releaseConfigurations.map(encodeReleaseConfiguration),
});

const encodeReleaseConfiguration = (configuration: ReleaseConfiguration) => ({
  release_version: configuration.releaseVersion,
  domains: configuration.domains,
  required_for: configuration.requiredFor,
});

export const useRegisterApp = (): UseMutationResult<void, unknown, RegisterAppRequest, unknown> => {
  const userId = useUserState().userId!;
  const appManagerClient = useAppManagerClient();
  return useMutation({
    mutationFn: async ({ configuration, release }) => {
      await appManagerClient.registerApp(
        userId,
        JSON.stringify(encodeConfiguration(configuration)),
        release
      );
    },
  });
};
