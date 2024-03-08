import { Loading } from 'common-frontend';
import { JSONValue } from 'common-frontend-utils';
import dayjs from 'dayjs';
import utc from 'dayjs/plugin/utc';
import React from 'react';

import { FormControl, Stack, Typography } from '@mui/material';

import { Tuple2 } from '@daml.js/26b14ad5a8a2ed45d75e3c774aeb1c41a918ef2f4a7d2bd40f9716f26c46bfdf/lib/DA/Types';
import { CoinConfig, USD } from '@daml.js/canton-coin/lib/CC/CoinConfig';

import { useSvcInfos } from '../../../contexts/SvContext';
import { DropdownSchedules } from '../../../utils/DropdownSchedules';
import { ActionFromForm } from '../VoteRequest';

dayjs.extend(utc);

const UpdateFutureCoinConfigSchedule: React.FC<{
  chooseAction: (action: ActionFromForm) => void;
}> = ({ chooseAction }) => {
  const svcInfosQuery = useSvcInfos();

  if (svcInfosQuery.isLoading) {
    return <Loading />;
  }

  if (svcInfosQuery.isError) {
    return <p>Error: {JSON.stringify(svcInfosQuery.error)}</p>;
  }

  if (!svcInfosQuery.data) {
    return <p>undefined query data</p>;
  }

  async function UpdateFutureCoinConfigScheduleAction(
    date: string,
    config: Record<string, JSONValue>
  ) {
    const decoded = CoinConfig(USD).decoder.run(config);
    if (decoded.ok) {
      const item: Tuple2<string, CoinConfig<'USD'>> = {
        _1: dayjs.utc(dayjs(date)).format('YYYY-MM-DDTHH:mm:00[Z]'),
        _2: decoded.result,
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
    } else {
      chooseAction({ formError: decoded.error });
    }
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
