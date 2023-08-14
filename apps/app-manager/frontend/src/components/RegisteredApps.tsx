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
  IconButton,
  Link,
  Stack,
  Typography,
} from '@mui/material';

import { useRegisterApp, useRegisteredApps } from '../hooks';
import { ConfigurationEditor } from './AppConfiguration';

const RegisteredApp: React.FC<{ app: openapi.RegisteredApp }> = ({ app }) => {
  return (
    <Card className="registered-app" variant="outlined">
      <CardContent>
        <Typography className="registered-app-name">{app.name}</Typography>
        <DirectoryEntry partyId={app.provider} />
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
  config.name !== '' && config.uiUrl !== '' && config.releaseConfigurations.length !== 0;

const RegisteredApps: React.FC = () => {
  const [appRelease, setAppRelease] = useState<File | null>(null);
  const [appConfig, setAppConfig] = useState<openapi.AppConfiguration>({
    version: 0,
    name: '',
    uiUrl: '',
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
          <Button
            id="register-app-button"
            onClick={() =>
              registerAppMutation.mutate({ configuration: appConfig, release: appRelease! })
            }
            disabled={!appRelease || !validAppConfiguration(appConfig)}
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
