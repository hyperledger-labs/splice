import { Loading } from 'common-frontend';
import { JSONValue } from 'common-frontend-utils';
import dayjs from 'dayjs';
import utc from 'dayjs/plugin/utc';
import React from 'react';

import { FormControl, Stack, Typography } from '@mui/material';

import { Tuple2 } from '@daml.js/87530dd1038863bad7bdf02c59ae851bc00f469edb2d7dbc8be3172daafa638c/lib/DA/Types';
import { CoinConfig, USD } from '@daml.js/canton-coin/lib/CC/CoinConfig';
import { ActionRequiringConfirmation } from '@daml.js/svc-governance/lib/CN/SvcRules/module';

import { useSvcInfos } from '../../../contexts/SvContext';
import { DropdownSchedules } from '../../../utils/DropdownSchedules';

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
    return <p>undefined query data</p>;
  }

  async function UpdateFutureCoinConfigScheduleAction(
    date: string,
    config: Record<string, JSONValue>
  ) {
    const item: Tuple2<string, CoinConfig<'USD'>> = {
      _1: dayjs.utc(dayjs(date)).format('YYYY-MM-DDTHH:mm:00[Z]'),
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
