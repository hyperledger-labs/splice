// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { Loading } from '@lfdecentralizedtrust/splice-common-frontend';
import { JSONValue } from '@lfdecentralizedtrust/splice-common-frontend-utils';
import dayjs from 'dayjs';
import utc from 'dayjs/plugin/utc';
import React from 'react';

import { FormControl, Stack, Typography } from '@mui/material';

import { Tuple2 } from '@daml.js/5aee9b21b8e9a4c4975b5f4c4198e6e6e8469df49e2010820e792f393db870f4/lib/DA/Types';
import { AmuletConfig, USD } from '@daml.js/splice-amulet/lib/Splice/AmuletConfig';

import { useDsoInfos } from '../../../contexts/SvContext';
import { DropdownSchedules } from '../../../utils/DropdownSchedules';
import { ActionFromForm } from '../VoteRequest';

dayjs.extend(utc);

const UpdateFutureAmuletConfigSchedule: React.FC<{
  chooseAction: (action: ActionFromForm) => void;
}> = ({ chooseAction }) => {
  const dsoInfosQuery = useDsoInfos();

  if (dsoInfosQuery.isLoading) {
    return <Loading />;
  }

  if (dsoInfosQuery.isError) {
    return <p>Error: {JSON.stringify(dsoInfosQuery.error)}</p>;
  }

  if (!dsoInfosQuery.data) {
    return <p>undefined query data</p>;
  }

  async function UpdateFutureAmuletConfigScheduleAction(
    date: string,
    config: Record<string, JSONValue>
  ) {
    const decoded = AmuletConfig(USD).decoder.run(config);
    if (decoded.ok) {
      const item: Tuple2<string, AmuletConfig<'USD'>> = {
        _1: dayjs.utc(dayjs(date)).format('YYYY-MM-DDTHH:mm:00[Z]'),
        _2: decoded.result,
      };
      chooseAction({
        tag: 'ARC_AmuletRules',
        value: {
          amuletRulesAction: {
            tag: 'CRARC_UpdateFutureAmuletConfigSchedule',
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
          futureValues={dsoInfosQuery.data.amuletRules.payload.configSchedule.futureValues}
          onChangeEditor={UpdateFutureAmuletConfigScheduleAction}
        />
      </FormControl>
    </Stack>
  );
};

export default UpdateFutureAmuletConfigSchedule;
