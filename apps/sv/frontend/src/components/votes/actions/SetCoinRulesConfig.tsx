import { getCoinConfigurationAsOfNow, Loading } from 'common-frontend';
import React from 'react';

import { FormControl, Stack, Typography } from '@mui/material';

import { CoinConfig } from '@daml.js/canton-coin-0.1.0/lib/CC/CoinConfig';
import { Schedule } from '@daml.js/canton-coin-0.1.0/lib/CC/Schedule';
import { ActionRequiringConfirmation } from '@daml.js/svc-governance/lib/CN/SvcRules/module';

import { useSvcInfos } from '../../../contexts/SvContext';
import ConfigurationNavigator from '../../../utils/ConfigurationNavigator';

const SetCoinConfig: React.FC<{
  chooseAction: (action: ActionRequiringConfirmation) => void;
}> = ({ chooseAction }) => {
  const svcInfosQuery = useSvcInfos();

  if (svcInfosQuery.isLoading) {
    return <Loading />;
  }

  if (svcInfosQuery.isError) {
    return <p>Not yet implemented.</p>;
  }

  if (!svcInfosQuery.data) {
    return <p>no VoteRequest contractId is specified</p>;
  }

  async function setCoinConfigAction(config: Schedule<string, CoinConfig<'USD'>>) {
    if (svcInfosQuery.isLoading) {
      return <Loading />;
    }

    if (svcInfosQuery.isError) {
      return <p>Not yet implemented.</p>;
    }

    if (!svcInfosQuery.data) {
      return <p>no VoteRequest contractId is specified</p>;
    }

    chooseAction({
      tag: 'ARC_CoinRules',
      value: {
        coinRulesAction: {
          tag: 'CRARC_SetConfigSchedule',
          value: { newConfigSchedule: config },
        },
      },
    });
  }

  return (
    <Stack direction="column" mb={4} spacing={1}>
      <Typography variant="h6">Configuration</Typography>
      <FormControl sx={{ marginRight: '32px', flexGrow: '1' }}>
        <ConfigurationNavigator
          data={getCoinConfigurationAsOfNow(svcInfosQuery.data.coinRules.payload.configSchedule)}
          onChange={setCoinConfigAction}
        />
      </FormControl>
    </Stack>
  );
};

export default SetCoinConfig;
