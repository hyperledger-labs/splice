// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import { Link as RouterLink } from 'react-router';
import { Box, Divider, TextField as MuiTextField, Typography } from '@mui/material';
import dayjs from 'dayjs';
import relativeTime from 'dayjs/plugin/relativeTime';
import { useFieldContext } from '../../hooks/formContext';
import type { ConfigChange, PendingConfigFieldInfo } from '../../utils/types';
import { nextScheduledSynchronizerUpgradeFormat } from '@lfdecentralizedtrust/splice-common-frontend-utils';

dayjs.extend(relativeTime);

export interface ConfigFieldProps {
  configChange: ConfigChange;
  effectiveDate?: string | undefined;
  pendingFieldInfo?: PendingConfigFieldInfo;
}

export type ConfigFieldState = {
  fieldName: string;
  value: string;
};

export const ConfigField: React.FC<ConfigFieldProps> = props => {
  const { configChange, effectiveDate, pendingFieldInfo } = props;
  const field = useFieldContext<ConfigFieldState>();

  const isSynchronizerUpgradeTime =
    field.state.value?.fieldName === 'nextScheduledSynchronizerUpgradeTime';
  const isSynchronizerUpgradeMigrationId =
    field.state.value?.fieldName === 'nextScheduledSynchronizerUpgradeMigrationId';

  // We disable the field if it is pending and the value is the default value.
  // The default value check is to handle the case where the user made a change
  // to the field before it became a field with pending changes.
  // This gives them the chance to revert that change.
  const isPendingAndDefaultValue =
    pendingFieldInfo !== undefined && field.state.meta.isDefaultValue;

  const isEffectiveAtThreshold = !effectiveDate;

  // When effective at Threshold, we disable the upgrade time and migrationId config fields
  const isEffectiveAtThresholdAndSyncUpgradeTimeOrMigrationId =
    isEffectiveAtThreshold && (isSynchronizerUpgradeTime || isSynchronizerUpgradeMigrationId);

  const isDisabled =
    isPendingAndDefaultValue || isEffectiveAtThresholdAndSyncUpgradeTimeOrMigrationId;

  const textFieldProps = {
    variant: 'outlined' as const,
    size: 'small' as const,
    color: field.state.meta.isDefaultValue ? ('primary' as const) : ('secondary' as const),
    focused: !field.state.meta.isDefaultValue,
    autoComplete: 'off' as const,
    inputProps: {
      sx: { textAlign: 'right' },
      'data-testid': `config-field-${configChange.fieldName}`,
    },
    disabled: isDisabled,
  };

  return (
    <>
      <Box
        sx={{
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'center',
        }}
      >
        <Typography variant="body1" data-testid={`config-label-${configChange.fieldName}`}>
          {configChange.label}
        </Typography>

        <Box sx={{ width: 250 }}>
          <MuiTextField
            {...textFieldProps}
            // We choose empty string to represent fields that could be undefined because their values have not been set.
            value={field.state.value?.value || ''}
            onBlur={field.handleBlur}
            onChange={e =>
              field.handleChange({
                fieldName: configChange.fieldName,
                value: e.target.value,
              })
            }
          />

          {!field.state.meta.isDefaultValue && (
            <Typography
              variant="caption"
              color="text.secondary"
              sx={{ mt: 0.5, display: 'block', textAlign: 'center' }}
              data-testid={`config-current-value-${configChange.fieldName}`}
            >
              Current Configuration: {configChange.currentValue}
            </Typography>
          )}

          {isSynchronizerUpgradeTime && (
            <SynchronizerUpgradeTimeDisplay
              effectiveDate={effectiveDate}
              configChange={configChange}
            />
          )}

          {pendingFieldInfo && <PendingConfigDisplay pendingFieldInfo={pendingFieldInfo} />}
        </Box>
      </Box>
      <Divider />
    </>
  );
};

interface PendingConfigDisplayProps {
  pendingFieldInfo: PendingConfigFieldInfo;
}

export const PendingConfigDisplay: React.FC<PendingConfigDisplayProps> = ({ pendingFieldInfo }) => {
  const { fieldName, pendingValue, proposalCid, effectiveDate } = pendingFieldInfo;
  const effectiveText =
    effectiveDate === 'Threshold' ? 'at Threshold' : dayjs(effectiveDate).fromNow();

  return (
    <Typography
      variant="caption"
      color="text.secondary"
      sx={{ mt: 0.5, display: 'block', textAlign: 'center' }}
      data-testid={`config-pending-value-${fieldName}`}
    >
      Pending Configuration: <strong>{pendingValue}</strong> <br />
      This{' '}
      <RouterLink
        to={`/governance-beta/proposals/${proposalCid}`}
        target="_blank"
        rel="noopener noreferrer"
      >
        pending configuration
      </RouterLink>{' '}
      will go into effect <strong>{effectiveText}</strong>
    </Typography>
  );
};

interface SynchronizerUpgradeTimeDisplayProps {
  effectiveDate: string | undefined;
  configChange: ConfigChange;
}

export const SynchronizerUpgradeTimeDisplay: React.FC<
  SynchronizerUpgradeTimeDisplayProps
> = props => {
  const { effectiveDate } = props;
  const defaultMigrationTime = dayjs(effectiveDate)
    .utc()
    .add(1, 'hour')
    .format(nextScheduledSynchronizerUpgradeFormat);

  return (
    <Typography
      variant="caption"
      color="text.secondary"
      sx={{ mt: 0.5, display: 'block', textAlign: 'center' }}
      data-testid={`next-scheduled-upgrade-time-default`}
    >
      {`Default: ${defaultMigrationTime}`}
    </Typography>
  );
};
