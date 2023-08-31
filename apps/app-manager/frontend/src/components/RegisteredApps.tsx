import * as openapi from 'validator-openapi';
import { DirectoryEntry, ErrorDisplay, Loading } from 'common-frontend';
import { MuiFileInput } from 'mui-file-input';
import React, { useState } from 'react';

import ContentCopyIcon from '@mui/icons-material/ContentCopy';
import {
  Button,
  Card,
  CardActions,
  CardContent,
  CardHeader,
  IconButton,
  Link,
  Stack,
  TextField,
  Typography,
} from '@mui/material';

import {
  usePublishAppRelease,
  useRegisterApp,
  useRegisteredApps,
  useUpdateAppConfiguration,
} from '../hooks';
import { ConfigurationEditor } from './AppConfiguration';

const RegisteredApp: React.FC<{ app: openapi.RegisteredApp }> = ({ app }) => {
  const [appRelease, setAppRelease] = useState<File | null>(null);
  const [updatedAppConfiguration, setUpdatedAppConfiguration] = useState<
    openapi.AppConfiguration | undefined
  >(undefined);
  const updateAppConfiguration = useUpdateAppConfiguration();
  const publishAppRelease = usePublishAppRelease();
  const onUpdateAppConfiguration = async () => {
    await updateAppConfiguration.mutateAsync({
      provider: app.provider,
      configuration: updatedAppConfiguration!,
    });
    setUpdatedAppConfiguration(undefined);
  };
  const onPublishAppRelease = async () => {
    await publishAppRelease.mutateAsync({ provider: app.provider, release: appRelease! });
    setAppRelease(null);
  };
  return (
    <Card className="registered-app" variant="outlined">
      <CardHeader className="registered-app-name" title={app.configuration.name} />
      <CardContent>
        <Stack direction="column" alignItems="flex-start" spacing={2}>
          <Stack direction="row" alignItems="center" spacing={2}>
            <Typography color="text.secondary">App Provider</Typography>
            <DirectoryEntry partyId={app.provider} />
          </Stack>
          <Stack direction="row" spacing={2}>
            {/* Setting the id does not seem to be possible here so we go for a classname. */}
            <MuiFileInput
              label="Release Bundle"
              inputProps={{ className: 'registered-app-release-bundle-input' }}
              value={appRelease}
              onChange={value => setAppRelease(value)}
            />
            <Button
              onClick={onPublishAppRelease}
              disabled={!appRelease}
              className="registered-app-publish-release-button"
            >
              Publish Release
            </Button>
          </Stack>
          {!updatedAppConfiguration && (
            <Button
              className="registered-app-edit-configuration-button"
              onClick={() =>
                setUpdatedAppConfiguration({
                  ...app.configuration,
                  version: app.configuration.version + 1,
                })
              }
            >
              Edit Configuration
            </Button>
          )}
          {updatedAppConfiguration && (
            <Card
              className="registered-app-configuration-update"
              sx={{ margin: '30px' }}
              variant="outlined"
            >
              <CardContent sx={{ padding: '60px' }}>
                <ConfigurationEditor
                  data={updatedAppConfiguration}
                  onChange={setUpdatedAppConfiguration}
                />
              </CardContent>
              <CardActions>
                <Button
                  onClick={onUpdateAppConfiguration}
                  className="registered-app-update-configuration-button"
                >
                  Update Configuration
                </Button>
                <Button color="warning" onClick={() => setUpdatedAppConfiguration(undefined)}>
                  Cancel
                </Button>
              </CardActions>
            </Card>
          )}
        </Stack>
      </CardContent>
      <CardActions>
        <Link href={app.appUrl} className="registered-app-link">
          Install URL
        </Link>
        <IconButton onClick={() => navigator.clipboard.writeText(app.appUrl)}>
          <ContentCopyIcon fontSize={'small'} />
        </IconButton>
      </CardActions>
    </Card>
  );
};

const validAppConfiguration = (config: openapi.AppConfiguration): boolean =>
  config.name !== '' && config.uiUri !== '' && config.releaseConfigurations.length !== 0;

const RegisteredApps: React.FC = () => {
  const [appProviderUser, setAppProviderUser] = useState<string>('');
  const [appRelease, setAppRelease] = useState<File | null>(null);
  const [appConfig, setAppConfig] = useState<openapi.AppConfiguration>({
    version: 0,
    name: '',
    uiUri: '',
    allowedRedirectUris: [],
    releaseConfigurations: [],
  });
  const registeredAppsQuery = useRegisteredApps();
  const registerAppMutation = useRegisterApp();

  return (
    <Stack mt={4} spacing={4} direction="column" justifyContent="center">
      <Typography mt={6} variant="h4">
        Registered Apps
      </Typography>
      <Card variant="outlined">
        <CardContent sx={{ paddingX: '64px' }}>
          <ConfigurationEditor data={appConfig} onChange={setAppConfig} />
          <Stack direction="column">
            <Typography variant="h5">App release bundle</Typography>
            {/* Setting the id does not seem to be possible here so we go for a classname. */}
            <MuiFileInput
              inputProps={{ className: 'register-app-release-bundle-input' }}
              value={appRelease}
              onChange={value => setAppRelease(value)}
            />
          </Stack>
          <TextField
            id="register-app-provider-user-input"
            label="App Provider User"
            value={appProviderUser}
            onChange={e => setAppProviderUser(e.target.value)}
          />
          <Button
            id="register-app-button"
            onClick={() =>
              registerAppMutation.mutate({
                configuration: appConfig,
                release: appRelease!,
                providerUserId: appProviderUser!,
              })
            }
            disabled={!appRelease || !validAppConfiguration(appConfig) || !appProviderUser}
          >
            Register app
          </Button>
        </CardContent>
      </Card>
      <Stack>
        {registeredAppsQuery.isLoading ? (
          <Loading />
        ) : registeredAppsQuery.isError ? (
          <ErrorDisplay
            message={`Failed to fetch registered apps: ${JSON.stringify(
              registeredAppsQuery.error
            )}`}
          />
        ) : (
          registeredAppsQuery.data.map(app => <RegisteredApp app={app} key={app.provider} />)
        )}
      </Stack>
    </Stack>
  );
};

export default RegisteredApps;
