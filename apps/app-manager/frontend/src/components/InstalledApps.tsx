import { ErrorDisplay, Loading } from 'common-frontend';
import React, { useState } from 'react';
import { InstalledApp as OpenAPIApp } from 'validator-openapi';

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

const addAppManagerContext = (url: URL): URL => {
  // Copy to avoid mutation
  const result = new URL(url);
  result.searchParams.set(
    'oidc_authority_url',
    `${config.services.validator.url}/app-manager/oauth2/`
  );
  result.searchParams.set('json_api_url', `${config.services.validator.url}/`);
  return result;
};

const InstalledApp: React.FC<{ app: OpenAPIApp }> = ({ app }) => {
  const redirectUri = addAppManagerContext(new URL(app.url));
  return (
    <Card className="installed-app" variant="outlined">
      <CardContent>
        <Typography className="installed-app-name">{app.name}</Typography>
      </CardContent>
      <CardActions>
        <Link href={redirectUri.toString()}>Launch</Link>
      </CardActions>
    </Card>
  );
};

const InstalledApps: React.FC = () => {
  const { data, error, isLoading, isError } = useInstalledApps();

  const [manifestUrl, setManifestUrl] = useState<string>('');

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
          value={manifestUrl}
          onChange={ev => setManifestUrl(ev.target.value)}
        />
        <Button id="install-app-button" onClick={() => installAppMutation.mutate(manifestUrl)}>
          Install app
        </Button>
      </Stack>
      <Stack></Stack>
      {isLoading ? (
        <Loading />
      ) : isError ? (
        <ErrorDisplay message={`Failed to fetch installed apps: ${JSON.stringify(error)}`} />
      ) : (
        data.map(app => <InstalledApp app={app} key={app.name} />)
      )}
    </Stack>
  );
};

export default InstalledApps;
