import * as openapi from 'validator-openapi';
import { DirectoryEntry, ErrorDisplay, Loading, appLaunchUrl } from 'common-frontend';
import React, { useState } from 'react';

import {
  Button,
  Card,
  CardActions,
  CardContent,
  Input,
  Link,
  Stack,
  Typography,
} from '@mui/material';

import { useInstallApp, useInstalledApps } from '../hooks';
import { config } from '../utils/config';

const InstalledApp: React.FC<{ app: openapi.InstalledApp }> = ({ app }) => {
  const redirectUri = appLaunchUrl(
    {
      oidcAuthority: `${config.services.validator.url}/app-manager/oauth2/`,
      jsonApi: `${config.services.validator.url}/`,
      wallet: config.services.wallet.uiUrl,
    },
    app.url
  );
  return (
    <Card className="installed-app" variant="outlined">
      <CardContent>
        <Typography className="installed-app-name">{app.name}</Typography>
        <DirectoryEntry partyId={app.provider} />
      </CardContent>
      <CardActions>
        <Link className="installed-app-link" href={redirectUri.toString()}>
          Launch
        </Link>
      </CardActions>
    </Card>
  );
};

const InstalledApps: React.FC = () => {
  const { data, error, isLoading, isError } = useInstalledApps();

  const [appUrl, setAppUrl] = useState<string>('');

  const installAppMutation = useInstallApp();

  return (
    <Stack mt={4} spacing={4} direction="column" justifyContent="center">
      <Typography mt={6} variant="h4">
        Installed Apps
      </Typography>
      <Stack direction="row">
        <Input
          id="install-app-input"
          type="text"
          value={appUrl}
          onChange={ev => setAppUrl(ev.target.value)}
        />
        <Button id="install-app-button" onClick={() => installAppMutation.mutate(appUrl)}>
          Install app
        </Button>
      </Stack>
      <Stack></Stack>
      {isLoading ? (
        <Loading />
      ) : isError ? (
        <ErrorDisplay message={`Failed to fetch installed apps: ${JSON.stringify(error)}`} />
      ) : (
        data.map(app => <InstalledApp app={app} key={app.provider} />)
      )}
    </Stack>
  );
};

export default InstalledApps;
