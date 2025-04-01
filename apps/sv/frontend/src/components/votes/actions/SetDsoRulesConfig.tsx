// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { Loading } from '@lfdecentralizedtrust/splice-common-frontend';
import {
  JSONValue,
  JsonEditor,
  JSONObject,
} from '@lfdecentralizedtrust/splice-common-frontend-utils';
import dayjs, { Dayjs } from 'dayjs';
import React, { useState, useEffect } from 'react';

import { Checkbox, FormControl, FormControlLabel, Stack, Typography } from '@mui/material';

import { DsoRulesConfig } from '@daml.js/splice-dso-governance/lib/Splice/DsoRules/module';

import { useDsoInfos } from '../../../contexts/SvContext';
import { ActionFromForm } from '../VoteRequest';

const SetDsoRulesConfig: React.FC<{
  expiration: Dayjs;
  chooseAction: (action: ActionFromForm) => void;
  setIsValidSynchronizerPauseTime: (isValid: boolean) => void;
}> = ({ expiration, chooseAction, setIsValidSynchronizerPauseTime }) => {
  const dsoInfosQuery = useDsoInfos();
  // TODO (#10209): remove this intermediate state by lifting it to VoteRequest.tsx
  const [configuration, setConfiguration] = useState<Record<string, JSONValue> | undefined>(
    undefined
  );

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

  const isScheduledDateValid = (scheduledDate: string) => {
    return dayjs(scheduledDate, dateFormat, true).isValid();
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
      const nextScheduledUpgrade = dayjs(nextScheduledSynchronizerUpgrade.time);
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
      if (
        !scheduled ||
        (typeof scheduled === 'object' &&
          isScheduledDateValid((scheduled as JSONObject).time as string))
      ) {
        chooseAction({
          tag: 'ARC_DsoRules',
          value: {
            dsoAction: {
              tag: 'SRARC_SetConfig',
              value: { newConfig: decoded.result },
            },
          },
        });
      } else {
        chooseAction({
          formError: {
            kind: 'DecoderError',
            input: '',
            at: '',
            message: 'Invalid date format of nextScheduledSynchronizerUpgrade',
          },
        });
      }
    } else {
      chooseAction({ formError: decoded.error });
    }
  }

  return (
    <Stack direction="column" mb={4} spacing={1}>
      <Typography variant="h6">Configuration</Typography>
      <FormControl sx={{ marginRight: '32px', flexGrow: '1' }}>
        <JsonEditor data={configuration} onChange={setDsoRulesConfigAction} />
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
