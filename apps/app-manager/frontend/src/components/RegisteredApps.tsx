import { ErrorDisplay, Loading } from 'common-frontend';
import { MuiFileInput } from 'mui-file-input';
import React, { useState } from 'react';
import { RegisteredApp as OpenAPIApp } from 'validator-openapi';

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

const RegisteredApp: React.FC<{ app: OpenAPIApp }> = ({ app }) => {
  return (
    <Card className="registered-app" variant="outlined">
      <CardContent>
        <Typography className="registered-app-name">{app.name}</Typography>
      </CardContent>
      <CardActions>
        <Link href={app.manifestUrl} className="registered-app-link">
          Install URL
        </Link>
        <IconButton onClick={() => navigator.clipboard.writeText(app.manifestUrl)}>
          <ContentCopyIcon fontSize={'small'} />
        </IconButton>
      </CardActions>
    </Card>
  );
};

const RegisteredApps: React.FC = () => {
  const [appBundle, setAppBundle] = useState<File | null>(null);

  const registeredAppsQuery = useRegisteredApps();
  const registerAppMutation = useRegisterApp();

  return (
    <Stack mt={4} spacing={4} direction="column" justifyContent="center">
      <Typography mt={6} variant="h4">
        Registered Apps
      </Typography>
      <Stack direction="row">
        {/* Setting the id does not seem to be possible here so we go for a classname. */}
        <MuiFileInput
          inputProps={{ className: 'app-bundle-input' }}
          value={appBundle}
          onChange={value => setAppBundle(value)}
        />
        <Button
          id="register-app-button"
          onClick={() => registerAppMutation.mutate(appBundle!)}
          disabled={!appBundle}
        >
          Register app
        </Button>
      </Stack>
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
          registeredAppsQuery.data.map(app => <RegisteredApp app={app} key={app.name} />)
        )}
      </Stack>
    </Stack>
  );
};

export default RegisteredApps;
