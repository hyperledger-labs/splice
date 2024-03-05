import { Loading } from 'common-frontend';
import { JSONValue, JsonEditor, JSONObject } from 'common-frontend-utils';
import dayjs from 'dayjs';
import React, { useState, useEffect } from 'react';

import { Checkbox, FormControl, FormControlLabel, Stack, Typography } from '@mui/material';

import { SvcRulesConfig } from '@daml.js/svc-governance/lib/CN/SvcRules/module';

import { useSvcInfos } from '../../../contexts/SvContext';
import { ActionFromForm } from '../VoteRequest';

const SetSvcRulesConfig: React.FC<{
  chooseAction: (action: ActionFromForm) => void;
}> = ({ chooseAction }) => {
  const svcInfosQuery = useSvcInfos();
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
    setSvcRulesConfigAction({
      ...configuration,
      nextScheduledDomainUpgrade,
    });
  };

  const removeDomainUpgradeSchedule = () => {
    setSvcRulesConfigAction({
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
    if (!configuration && svcInfosQuery.isSuccess) {
      setConfiguration(
        SvcRulesConfig.encode(svcInfosQuery.data?.svcRules.payload.config!) as Record<
          string,
          JSONValue
        >
      );
      setNextScheduledDomainUpgrade(
        !!svcInfosQuery.data?.svcRules.payload.config.nextScheduledDomainUpgrade
      );
    }
  }, [configuration, svcInfosQuery]);

  if (svcInfosQuery.isError) {
    return <p>Error: {JSON.stringify(svcInfosQuery.error)}</p>;
  }

  if (!configuration) {
    return <Loading />;
  }

  async function setSvcRulesConfigAction(svcRulesConfig: Record<string, JSONValue>) {
    setConfiguration(svcRulesConfig);
    const decoded = SvcRulesConfig.decoder.run(svcRulesConfig);
    if (decoded.ok) {
      const scheduled = svcRulesConfig.nextScheduledDomainUpgrade;
      if (
        !scheduled ||
        (typeof scheduled === 'object' &&
          isScheduledDateValid((scheduled as JSONObject).time as string))
      ) {
        chooseAction({
          tag: 'ARC_SvcRules',
          value: {
            svcAction: {
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
        <JsonEditor data={configuration} onChange={setSvcRulesConfigAction} />
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

export default SetSvcRulesConfig;
