import { Loading } from 'common-frontend';
import React from 'react';

import { Stack, Typography } from '@mui/material';

import { ActionRequiringConfirmation } from '@daml.js/svc-governance/lib/CN/SvcRules/module';

import { useSvcInfos } from '../../../contexts/SvContext';
import { DropdownSchedules } from '../../../utils/DropdownSchedules';

const dayjs = require('dayjs');
const utc = require('dayjs/plugin/utc');
dayjs.extend(utc);

const RemoveFutureCoinConfigSchedule: React.FC<{
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

  async function RemoveFutureCoinConfigScheduleAction(time: string) {
    chooseAction({
      tag: 'ARC_CoinRules',
      value: {
        coinRulesAction: {
          tag: 'CRARC_RemoveFutureCoinConfigSchedule',
          value: { scheduleTime: dayjs.utc(dayjs(time)).format('YYYY-MM-DDTHH:mm:00[Z]') },
        },
      },
    });
  }

  return (
    <Stack direction="column" mb={4} spacing={1}>
      <Typography variant="h6">Schedules</Typography>
      <DropdownSchedules
        futureValues={svcInfosQuery.data.coinRules.payload.configSchedule.futureValues}
        onChange={RemoveFutureCoinConfigScheduleAction}
      />
    </Stack>
  );
};

export default RemoveFutureCoinConfigSchedule;
