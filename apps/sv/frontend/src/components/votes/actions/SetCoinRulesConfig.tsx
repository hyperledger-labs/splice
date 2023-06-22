import { Loading } from 'common-frontend';
import React from 'react';

import { FormControl, Stack, Typography } from '@mui/material';

import { Tuple2 } from '@daml.js/40f452260bef3f29dede136108fc08a88d5a5250310281067087da6f0baddff7/lib/DA/Types';
import { CoinConfig, USD } from '@daml.js/canton-coin-0.1.0/lib/CC/CoinConfig';
import { Schedule } from '@daml.js/canton-coin-0.1.0/lib/CC/Schedule';
import { ActionRequiringConfirmation } from '@daml.js/svc-governance/lib/CN/SvcRules/module';
import { Time } from '@daml/types';

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

  async function setCoinConfigAction(config: Tuple2<Time, CoinConfig<USD>>[]) {
    const mergedConfig: Schedule<Time, CoinConfig<USD>> = {
      initialValue: svcInfosQuery.data?.coinRules.payload.configSchedule.initialValue!,
      futureValues: config,
    };
    chooseAction({
      tag: 'ARC_CoinRules',
      value: {
        coinRulesCid: svcInfosQuery.data?.coinRules.contractId!,
        coinRulesAction: {
          tag: 'CRARC_SetConfigSchedule',
          value: { newConfigSchedule: mergedConfig },
        },
      },
    });
  }

  return (
    <Stack direction="column" mb={4} spacing={1}>
      <Typography variant="h6">Configuration</Typography>
      <FormControl sx={{ marginRight: '32px', flexGrow: '1' }}>
        <ConfigurationNavigator
          data={svcInfosQuery.data?.coinRules.payload.configSchedule!}
          onChange={setCoinConfigAction}
        />
      </FormControl>
    </Stack>
  );
};

export default SetCoinConfig;
