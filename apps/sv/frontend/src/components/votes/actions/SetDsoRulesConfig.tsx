import { Loading } from 'common-frontend';
import { JSONValue, JsonEditor, JSONObject } from 'common-frontend-utils';
import dayjs from 'dayjs';
import React, { useState, useEffect } from 'react';

import { Checkbox, FormControl, FormControlLabel, Stack, Typography } from '@mui/material';

import { DsoRulesConfig } from '@daml.js/dso-governance/lib/CN/DsoRules/module';

import { useDsoInfos } from '../../../contexts/SvContext';
import { ActionFromForm } from '../VoteRequest';

const SetDsoRulesConfig: React.FC<{
  chooseAction: (action: ActionFromForm) => void;
}> = ({ chooseAction }) => {
  const dsoInfosQuery = useDsoInfos();
  // TODO (#10209): remove this intermediate state by lifting it to VoteRequest.tsx
  const [configuration, setConfiguration] = useState<Record<string, JSONValue> | undefined>(
    undefined
  );
  const [nextScheduledDomainUpgrade, setNextScheduledDomainUpgrade] = useState<boolean>(false);

  const dateFormat = 'YYYY-MM-DDTHH:mm:ss[Z]';

  const addDefaultDomainUpgradeSchedule = () => {
    const nextScheduledDomainUpgrade = {
      // default schedule an domain upgrade in 3 days
      time: dayjs.utc().add(3, 'day').format(dateFormat),
      migrationId: '1',
    };
    setDsoRulesConfigAction({
      ...configuration,
      nextScheduledDomainUpgrade,
    });
  };

  const removeDomainUpgradeSchedule = () => {
    setDsoRulesConfigAction({
      ...configuration,
      nextScheduledDomainUpgrade: null,
    });
  };

  const handleDomainUpgradeChange = (event: React.ChangeEvent<HTMLInputElement>) => {
    const checked = event.target.checked;
    if (checked) {
      addDefaultDomainUpgradeSchedule();
    } else {
      removeDomainUpgradeSchedule();
    }
    setNextScheduledDomainUpgrade(checked);
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
      setNextScheduledDomainUpgrade(
        !!dsoInfosQuery.data?.dsoRules.payload.config.nextScheduledDomainUpgrade
      );
    }
  }, [configuration, dsoInfosQuery]);

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
      const scheduled = dsoRulesConfig.nextScheduledDomainUpgrade;
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
            message: 'Invalid date format of nextScheduledDomainUpgrade',
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
              checked={nextScheduledDomainUpgrade}
              onChange={handleDomainUpgradeChange}
              inputProps={{ 'aria-label': 'controlled' }}
              data-testid="enable-next-scheduled-domain-upgrade"
            />
          }
          label="Set next scheduled domain upgrade"
        />
      </FormControl>
    </Stack>
  );
};

export default SetDsoRulesConfig;
