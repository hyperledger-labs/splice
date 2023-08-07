import { JSONValue, Loading } from 'common-frontend';
import React from 'react';

import { FormControl, Stack, Typography } from '@mui/material';

import { Tuple2 } from '@daml.js/40f452260bef3f29dede136108fc08a88d5a5250310281067087da6f0baddff7/lib/DA/Types';
import { CoinConfig, USD } from '@daml.js/canton-coin-0.1.0/lib/CC/CoinConfig';
import { ActionRequiringConfirmation } from '@daml.js/svc-governance/lib/CN/SvcRules/module';

import { useSvcInfos } from '../../../contexts/SvContext';
import { DropdownSchedules } from '../../../utils/DropdownSchedules';

const dayjs = require('dayjs');
const utc = require('dayjs/plugin/utc');
dayjs.extend(utc);

const UpdateFutureCoinConfigSchedule: React.FC<{
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

  async function UpdateFutureCoinConfigScheduleAction(
    date: string,
    config: Record<string, JSONValue>
  ) {
    const item: Tuple2<string, CoinConfig<'USD'>> = {
      _1: dayjs.utc(date).format('YYYY-MM-DDTHH:mm:00[Z]'),
      _2: CoinConfig(USD).decoder.runWithException(config),
    };

    chooseAction({
      tag: 'ARC_CoinRules',
      value: {
        coinRulesAction: {
          tag: 'CRARC_UpdateFutureCoinConfigSchedule',
          value: { scheduleItem: item },
        },
      },
    });
  }

  return (
    <Stack direction="column" mb={4} spacing={1}>
      <Typography variant="h6">Configuration</Typography>
      <FormControl sx={{ marginRight: '32px', flexGrow: '1' }}>
        <DropdownSchedules
          futureValues={svcInfosQuery.data.coinRules.payload.configSchedule.futureValues}
          onChangeEditor={UpdateFutureCoinConfigScheduleAction}
        />
      </FormControl>
    </Stack>
  );
};

export default UpdateFutureCoinConfigSchedule;
