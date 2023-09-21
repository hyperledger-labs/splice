import { Loading } from 'common-frontend';
import dayjs from 'dayjs';
import utc from 'dayjs/plugin/utc';
import React from 'react';

import { Stack, Typography } from '@mui/material';

import { ActionRequiringConfirmation } from '@daml.js/svc-governance/lib/CN/SvcRules/module';

import { useSvcInfos } from '../../../contexts/SvContext';
import { DropdownSchedules } from '../../../utils/DropdownSchedules';

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
    return <p>undefined query data</p>;
  }

  async function RemoveFutureCoinConfigScheduleAction(time: string) {
    const utcTime = dayjs.utc(dayjs(time)).format('YYYY-MM-DDTHH:mm:00[Z]');
    chooseAction({
      tag: 'ARC_CoinRules',
      value: {
        coinRulesAction: {
          tag: 'CRARC_RemoveFutureCoinConfigSchedule',
          value: { scheduleTime: utcTime },
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
