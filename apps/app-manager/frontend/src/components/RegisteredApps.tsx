import * as openapi from 'validator-openapi';
import { DirectoryEntry, ErrorDisplay, Loading } from 'common-frontend';
import { MuiFileInput } from 'mui-file-input';
import React, { useState } from 'react';
import { Domain } from 'validator-openapi';

import ContentCopyIcon from '@mui/icons-material/ContentCopy';
import {
  Button,
  Card,
  CardActions,
  CardContent,
  IconButton,
  Link,
  OutlinedInput,
  Stack,
  Typography,
} from '@mui/material';

import { useRegisterApp, useRegisteredApps } from '../hooks';

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

const DomainInput: React.FC<{ data: Domain; setData: (newData: Domain) => void }> = ({
  data,
  setData,
}) => {
  return (
    <Stack direction="row">
      <OutlinedInput
        inputProps={{ className: 'register-app-domain-alias-input' }}
        label="alias"
        value={data.alias}
        onChange={e => setData({ alias: e.target.value, url: data.url })}
      />
      <OutlinedInput
        inputProps={{ className: 'register-app-domain-url-input' }}
        label="url"
        value={data.url}
        onChange={e => setData({ alias: data.alias, url: e.target.value })}
      />
    </Stack>
  );
};

const RegisteredApps: React.FC = () => {
  const [appRelease, setAppRelease] = useState<File | null>(null);
  const [name, setName] = useState<string>('');
  const [uiUrl, setUiUrl] = useState<string>('');
  const [domains, setDomains] = useState<Domain[]>([]);

  const setDomain = (i: number) => (data: Domain) => {
    const updated = [...domains.slice(0, i), data, ...domains.slice(i + 1)];
    setDomains(updated);
  };

  const addDomain = () => {
    setDomains([...domains, { alias: '', url: '' }]);
  };

  const registeredAppsQuery = useRegisteredApps();
  const registerAppMutation = useRegisterApp();

  return (
    <Stack mt={4} spacing={4} direction="column" justifyContent="center">
      <Typography mt={6} variant="h4">
        Registered Apps
      </Typography>
      <Card variant="outlined">
        <CardContent sx={{ paddingX: '64px' }}>
          <Stack direction="column">
            <Typography variant="h6">App name</Typography>
            <OutlinedInput
              id="register-app-name-input"
              type="text"
              value={name}
              onChange={e => setName(e.target.value)}
            />
          </Stack>
          <Stack direction="column">
            <Typography variant="h6">App UI URL</Typography>
            <OutlinedInput
              id="register-app-ui-url-input"
              type="text"
              value={uiUrl}
              onChange={e => setUiUrl(e.target.value)}
            />
          </Stack>
          <Stack direction="column">
            <Typography variant="h6">App release bundle</Typography>
            {/* Setting the id does not seem to be possible here so we go for a classname. */}
            <MuiFileInput
              inputProps={{ className: 'register-app-release-bundle-input' }}
              value={appRelease}
              onChange={value => setAppRelease(value)}
            />
          </Stack>
          <Button id="register-app-add-domain-button" onClick={addDomain}>
            Add Domain
          </Button>
          {domains.map((domain, i) => (
            <DomainInput data={domain} setData={setDomain(i)} key={i} />
          ))}
          <Button
            id="register-app-button"
            onClick={() =>
              registerAppMutation.mutate({ name, uiUrl, domains, release: appRelease! })
            }
            disabled={!appRelease}
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
