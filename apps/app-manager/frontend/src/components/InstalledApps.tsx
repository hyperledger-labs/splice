import * as openapi from 'validator-openapi';
import { DateDisplay, AnsEntry, ErrorDisplay, Loading, appLaunchUrl } from 'common-frontend';
import React, { useState } from 'react';

import {
  Button,
  Card,
  CardActions,
  CardContent,
  Input,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableRow,
  Typography,
} from '@mui/material';

import { useAppManagerClient } from '../contexts/AppManagerServiceContext';
import { useApproveAppReleaseConfiguration, useInstallApp, useInstalledApps } from '../hooks';
import { useAppManagerConfig } from '../utils/config';

const Timespan: React.FC<{ timespan: openapi.Timespan }> = ({ timespan }) => (
  <Stack direction="row">
    <Typography variant="body1">valid from &nbsp;</Typography>
    {timespan._from ? (
      <DateDisplay datetime={timespan._from} />
    ) : (
      <Typography variant="body1">-∞</Typography>
    )}
    <Typography variant="body1">&nbsp; until &nbsp;</Typography>
    {timespan._from ? (
      <DateDisplay datetime={timespan._from} />
    ) : (
      <Typography variant="body1">∞</Typography>
    )}
  </Stack>
);

const ReleaseConfiguration: React.FC<{ config: openapi.ReleaseConfiguration }> = ({ config }) => {
  return (
    <Stack sx={{ margin: '20px' }} spacing={2} className="release-configuration">
      <Timespan timespan={config.required_for} />
      <Stack direction="row">
        <Typography variant="h6">Release version: {config.release_version}</Typography>
      </Stack>
      <Typography variant="h6">Required Domains</Typography>
      <Table>
        <TableHead>
          <TableRow>
            <TableCell>Domain Alias</TableCell>
            <TableCell>Domain URL</TableCell>
          </TableRow>
        </TableHead>
        <TableBody>
          {config.domains.map((domain, i) => (
            <TableRow key={i}>
              <TableCell>{domain.alias}</TableCell>
              <TableCell>{domain.url}</TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </Stack>
  );
};

const ApprovedReleaseConfiguration: React.FC<{ config: openapi.ReleaseConfiguration }> = ({
  config,
}) => {
  return (
    <Card variant="outlined" className="approved-release-configuration">
      <ReleaseConfiguration config={config} />
    </Card>
  );
};

const UnapprovedReleaseConfiguration: React.FC<{
  provider: string;
  configurationVersion: number;
  config: openapi.UnapprovedReleaseConfiguration;
}> = ({ provider, configurationVersion, config }) => {
  const approveAppReleaseConfiguration = useApproveAppReleaseConfiguration();
  return (
    <Card variant="outlined" className="unapproved-release-configuration">
      <ReleaseConfiguration config={config.release_configuration} />
      <Button
        className="approve-release-configuration-button"
        onClick={() =>
          approveAppReleaseConfiguration.mutate({
            provider,
            configuration_version: configurationVersion,
            release_configuration_index: config.release_configuration_index,
          })
        }
      >
        Approve
      </Button>
    </Card>
  );
};

const InstalledApp: React.FC<{ app: openapi.InstalledApp }> = ({ app }) => {
  const config = useAppManagerConfig();
  const redirectUri = appLaunchUrl(
    {
      oidcAuthority: `${config.services.validator.url}/v0/app-manager/oauth2/`,
      jsonApi: `${config.services.validator.url}/jsonApiProxy/`,
      wallet: config.services.wallet.uiUrl,
      clientId: app.provider,
    },
    app.latest_configuration.ui_uri
  );
  const client = useAppManagerClient();
  const onLaunch = async (_e: React.MouseEvent) => {
    await client.authorizeApp(app.provider);
    window.location.href = redirectUri;
  };
  return (
    <Card className="installed-app" variant="outlined">
      <CardContent>
        <Typography className="installed-app-name">{app.latest_configuration.name}</Typography>
        <AnsEntry partyId={app.provider} />
        <Typography variant="h5">Unapproved Release Configurations</Typography>
        {app.unapproved_release_configurations.map((config, i) => (
          <UnapprovedReleaseConfiguration
            key={i}
            config={config}
            provider={app.provider}
            configurationVersion={app.latest_configuration.version}
          />
        ))}
        <Typography variant="h5">Approved Release Configurations</Typography>
        {app.approved_release_configurations.map((config, i) => (
          <ApprovedReleaseConfiguration key={i} config={config} />
        ))}
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
