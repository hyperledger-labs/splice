import { Loading } from 'common-frontend';
import { JSONValue } from 'common-frontend-utils';
import dayjs from 'dayjs';
import utc from 'dayjs/plugin/utc';
import React from 'react';

import { FormControl, Stack, Typography } from '@mui/material';

import { Tuple2 } from '@daml.js/5aee9b21b8e9a4c4975b5f4c4198e6e6e8469df49e2010820e792f393db870f4/lib/DA/Types';
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
