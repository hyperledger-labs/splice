import * as openapi from 'validator-openapi';
import { DirectoryEntry, ErrorDisplay, Loading, appLaunchUrl, useUserState } from 'common-frontend';
import React, { useState } from 'react';

import { Button, Card, CardActions, CardContent, Input, Stack, Typography } from '@mui/material';

import { useAppManagerClient } from '../contexts/AppManagerServiceContext';
import { useInstallApp, useInstalledApps } from '../hooks';
import { config } from '../utils/config';

const InstalledApp: React.FC<{ app: openapi.InstalledApp }> = ({ app }) => {
  const redirectUri = appLaunchUrl(
    {
      oidcAuthority: `${config.services.validator.url}/app-manager/oauth2/`,
      jsonApi: `${config.services.validator.url}/`,
      wallet: config.services.wallet.uiUrl,
      clientId: app.provider,
    },
    app.url
  );
  const client = useAppManagerClient();
  const { userId } = useUserState();
  const onLaunch = async (e: React.MouseEvent) => {
    await client.authorizeApp(app.provider, userId!);
    window.location.href = redirectUri;
  };
  return (
    <Card className="installed-app" variant="outlined">
      <CardContent>
        <Typography className="installed-app-name">{app.name}</Typography>
        <DirectoryEntry partyId={app.provider} />
      </CardContent>
      <CardActions>
        <Button className="installed-app-link" onClick={e => onLaunch(e)}>
          Launch
        </Button>
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
