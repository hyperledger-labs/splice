// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { Loading } from '@lfdecentralizedtrust/splice-common-frontend';
import {
  JsonEditor,
  JSONObject,
  JSONValue,
} from '@lfdecentralizedtrust/splice-common-frontend-utils';
import dayjs, { Dayjs } from 'dayjs';
import React, { useEffect, useState } from 'react';

import { Checkbox, FormControl, FormControlLabel, Stack, Typography } from '@mui/material';

import { DsoRulesConfig } from '@daml.js/splice-dso-governance/lib/Splice/DsoRules/module';

import { useDsoInfos } from '../../../contexts/SvContext';
import { ActionFromForm } from '../VoteRequest';

const SetDsoRulesConfig: React.FC<{
  chooseAction: (action: ActionFromForm) => void;
  setIsValidSynchronizerPauseTime: (isValid: boolean) => void;
  expiration: Dayjs;
  effectivity: Dayjs;
  isEffective: boolean;
}> = ({ chooseAction, setIsValidSynchronizerPauseTime, expiration, effectivity, isEffective }) => {
  const dsoInfosQuery = useDsoInfos();
  // TODO (#967): remove this intermediate state by lifting it to VoteRequest.tsx
  const [configuration, setConfiguration] = useState<Record<string, JSONValue> | undefined>(
    undefined
  );

  // This format should only be used when working with a datetime already in utc
  const dateFormat = 'YYYY-MM-DDTHH:mm:ss[Z]';
  const defaultNextScheduledSynchronizerUpgrade = {
    time: dayjs.utc().add(3, 'day').format(dateFormat),
    migrationId: '1',
  };
  const [nextScheduledSynchronizerUpgrade, setNextScheduledSynchronizerUpgrade] = useState(
    defaultNextScheduledSynchronizerUpgrade
  );
  const [shouldScheduleDomainUpgrade] = useState<boolean>(false);

  const handleSynchronizerUpgradeChange = (event: React.ChangeEvent<HTMLInputElement>) => {
    const checked = event.target.checked;
    if (checked) {
      const c = {
        ...configuration,
        nextScheduledSynchronizerUpgrade,
      };
      setDsoRulesConfigAction(c);
    } else {
      const c = {
        ...configuration,
        nextScheduledSynchronizerUpgrade: null,
      };
      setDsoRulesConfigAction(c);
      setIsValidSynchronizerPauseTime(true);
    }
  };

  const isScheduledDateValid = (
    scheduledDate: string,
    newConfig: DsoRulesConfig,
    baseConfig: DsoRulesConfig | null
  ) => {
    const scheduledDateTime = dayjs.utc(scheduledDate, dateFormat, true);
    const isFormatValid = scheduledDateTime.isValid();
    const isValidAgainstEffectivity = isEffective ? effectivity.isBefore(scheduledDateTime) : true;
    const isValidAgainstExpiration = expiration.isBefore(scheduledDateTime);
    const scheduleDateTimeIsPastExpirationAndEffectivity =
      isValidAgainstEffectivity && isValidAgainstExpiration;

    // If the scheduled date changes, we validate that it's after the expiration and effectivity dates
    const isScheduledDateValid = baseConfig
      ? newConfig?.nextScheduledSynchronizerUpgrade ===
          baseConfig?.nextScheduledSynchronizerUpgrade ||
        scheduleDateTimeIsPastExpirationAndEffectivity
      : scheduleDateTimeIsPastExpirationAndEffectivity;

    return isFormatValid && isScheduledDateValid;
  };

  useEffect(() => {
    if (!configuration && dsoInfosQuery.isSuccess) {
      setConfiguration(
        DsoRulesConfig.encode(dsoInfosQuery.data?.dsoRules.payload.config!) as Record<
          string,
          JSONValue
        >
      );
      const upgradeConfig =
        dsoInfosQuery.data?.dsoRules.payload.config.nextScheduledSynchronizerUpgrade;
      if (upgradeConfig) {
        setNextScheduledSynchronizerUpgrade(upgradeConfig);
      }
    }

    if (configuration && configuration.nextScheduledSynchronizerUpgrade) {
      const nextScheduledUpgrade = dayjs.utc(nextScheduledSynchronizerUpgrade.time);
      // eslint-disable-next-line @typescript-eslint/no-unused-expressions
      expiration.isBefore(nextScheduledUpgrade)
        ? setIsValidSynchronizerPauseTime(true)
        : setIsValidSynchronizerPauseTime(false);
    }
  }, [
    configuration,
    dsoInfosQuery,
    expiration,
    nextScheduledSynchronizerUpgrade.time,
    setIsValidSynchronizerPauseTime,
  ]);

  if (dsoInfosQuery.isError) {
    return <p>Error: {JSON.stringify(dsoInfosQuery.error)}</p>;
  }

  if (!configuration) {
    return <Loading />;
  }

  async function setDsoRulesConfigAction(dsoRulesConfig: Record<string, JSONValue>) {
    setConfiguration(dsoRulesConfig);
    const decoded = DsoRulesConfig.decoder.run(dsoRulesConfig);
    if (decoded.ok) {
      const scheduled = dsoRulesConfig.nextScheduledSynchronizerUpgrade;
      const newConfig = decoded.result;
      const baseConfig = dsoInfosQuery.data?.dsoRules.payload.config || null;

      chooseAction({
        tag: 'ARC_DsoRules',
        value: {
          dsoAction: {
            tag: 'SRARC_SetConfig',
            value: {
              newConfig: newConfig,
              baseConfig: baseConfig,
            },
          },
        },
      });

      const isValidSynchronizerPauseTime =
        !scheduled ||
        (typeof scheduled === 'object' &&
          isScheduledDateValid((scheduled as JSONObject).time as string, newConfig, baseConfig));

      setIsValidSynchronizerPauseTime(isValidSynchronizerPauseTime);
    } else {
      chooseAction({ formError: decoded.error });
    }
  }

  return (
    <Stack direction="column" mb={4} spacing={1}>
      <Typography variant="h6" data-testid="set-dso-rules-config-header">
        Configuration
      </Typography>
      <FormControl sx={{ marginRight: '32px', flexGrow: '1' }}>
        <JsonEditor
          data={configuration}
          onChange={setDsoRulesConfigAction}
          disabledKeys={['dsoDelegateInactiveTimeout.microseconds']}
        />
        <FormControlLabel
          control={
            <Checkbox
              id={'enable-next-scheduled-domain-upgrade'}
              value={shouldScheduleDomainUpgrade}
              onChange={handleSynchronizerUpgradeChange}
              inputProps={{ 'aria-label': 'controlled' }}
              data-testid="enable-next-scheduled-domain-upgrade"
            />
          }
          label="Set next scheduled synchronizer upgrade"
        />
      </FormControl>
    </Stack>
  );
};

export default SetDsoRulesConfig;
