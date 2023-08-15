import { UseMutationResult, useMutation } from '@tanstack/react-query';
import { AppConfiguration, HttpFile, ReleaseConfiguration } from 'validator-openapi';

import { useAppManagerAdminClient } from '../../contexts/AppManagerServiceContext';

export type RegisterAppRequest = {
  configuration: AppConfiguration;
  release: HttpFile;
  providerUserId: string;
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
  const appManagerClient = useAppManagerAdminClient();
  return useMutation({
    mutationFn: async ({ configuration, release, providerUserId }) => {
      await appManagerClient.registerApp(
        providerUserId,
        JSON.stringify(encodeConfiguration(configuration)),
        release
      );
    },
  });
};
