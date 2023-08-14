import * as openapi from 'validator-openapi';

import { Button, Card, CardContent, Stack, TextField, Typography } from '@mui/material';
import { DesktopDateTimePicker } from '@mui/x-date-pickers/DesktopDateTimePicker';

const DomainInput: React.FC<{
  data: openapi.Domain;
  onChange: (newData: openapi.Domain) => void;
}> = ({ data, onChange }) => {
  return (
    <Stack direction="row">
      <TextField
        inputProps={{ className: 'domain-alias-input' }}
        label="alias"
        value={data.alias}
        onChange={e => onChange({ ...data, alias: e.target.value })}
      />
      <TextField
        inputProps={{ className: 'domain-url-input' }}
        label="url"
        value={data.url}
        onChange={e => onChange({ ...data, url: e.target.value })}
      />
    </Stack>
  );
};

export type ReleaseConfigurationEditorProps = {
  data: openapi.ReleaseConfiguration;
  onChange: (data: openapi.ReleaseConfiguration) => void;
};

const ReleaseConfigurationEditor: React.FC<ReleaseConfigurationEditorProps> = ({
  data,
  onChange,
}) => {
  const onDomainChange = (domain: openapi.Domain, i: number) =>
    onChange({
      ...data,
      domains: [...data.domains.slice(0, i), domain, ...data.domains.slice(i + 1)],
    });
  const onAddDomain = () =>
    onChange({ ...data, domains: [...data.domains, { alias: '', url: '' }] });
  return (
    <Card variant="outlined" sx={{ margin: '30px' }}>
      <CardContent sx={{ paddingX: '64px' }}>
        <TextField
          inputProps={{ className: 'release-config-release-version-input' }}
          label="Release version"
          type="text"
          value={data.releaseVersion}
          onChange={e => onChange({ ...data, releaseVersion: e.target.value })}
        />
        <DesktopDateTimePicker
          label={`Enter start time`}
          value={data.requiredFor._from}
          readOnly={false}
          onChange={date =>
            onChange({ ...data, requiredFor: { ...data.requiredFor, _from: date || undefined } })
          }
          slotProps={{
            textField: {
              className: 'release-config-from-input',
            },
          }}
          closeOnSelect
        />
        <DesktopDateTimePicker
          label={`Enter end time`}
          value={data.requiredFor.to}
          readOnly={false}
          onChange={date =>
            onChange({ ...data, requiredFor: { ...data.requiredFor, to: date || undefined } })
          }
          slotProps={{
            textField: {
              className: 'release-config-to-input',
            },
          }}
          closeOnSelect
        />
        <Button className="release-config-add-domain-button" onClick={onAddDomain}>
          Add Domain
        </Button>
        {data.domains.map((domain, i) => (
          <DomainInput data={domain} onChange={domain => onDomainChange(domain, i)} key={i} />
        ))}
      </CardContent>
    </Card>
  );
};

export type ConfigurationEditorProps = {
  data: openapi.AppConfiguration;
  onChange: (data: openapi.AppConfiguration) => void;
};

export const ConfigurationEditor: React.FC<ConfigurationEditorProps> = ({ data, onChange }) => {
  const onReleaseConfigChange = (releaseConfig: openapi.ReleaseConfiguration, i: number) =>
    onChange({
      ...data,
      releaseConfigurations: [
        ...data.releaseConfigurations.slice(0, i),
        releaseConfig,
        ...data.releaseConfigurations.slice(i + 1),
      ],
    });
  const onAddReleaseConfiguration = () =>
    onChange({
      ...data,
      releaseConfigurations: [
        ...data.releaseConfigurations,
        { domains: [], releaseVersion: '', requiredFor: {} },
      ],
    });

  return (
    <Card variant="outlined">
      <CardContent>
        <Stack direction="column" spacing={2}>
          <Typography variant="h5">App Configuration</Typography>
          <TextField
            inputProps={{ className: 'app-config-name-input' }}
            type="text"
            label="App name"
            value={data.name}
            onChange={e => onChange({ ...data, name: e.target.value })}
          />
          <TextField
            inputProps={{ className: 'app-config-ui-url-input' }}
            label="App UI URL"
            type="text"
            value={data.uiUrl}
            onChange={e => onChange({ ...data, uiUrl: e.target.value })}
          />
          <Stack direction="column">
            <Typography variant="h6">Release configurations</Typography>
            <Button className="add-release-configuration" onClick={onAddReleaseConfiguration}>
              Add release configuration
            </Button>
            {data.releaseConfigurations.map((config, i) => (
              <ReleaseConfigurationEditor
                data={config}
                onChange={data => onReleaseConfigChange(data, i)}
                key={i}
              />
            ))}
          </Stack>
        </Stack>
      </CardContent>
    </Card>
  );
};
