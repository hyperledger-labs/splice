// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { Loading } from '@lfdecentralizedtrust/splice-common-frontend';
import dayjs from 'dayjs';
import utc from 'dayjs/plugin/utc';
import React from 'react';

import { Stack, Typography } from '@mui/material';

import { ActionRequiringConfirmation } from '@daml.js/splice-dso-governance/lib/Splice/DsoRules/module';

import { useDsoInfos } from '../../../contexts/SvContext';
import { DropdownSchedules } from '../../../utils/DropdownSchedules';

dayjs.extend(utc);

const RemoveFutureAmuletConfigSchedule: React.FC<{
  chooseAction: (action: ActionRequiringConfirmation) => void;
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

  async function RemoveFutureAmuletConfigScheduleAction(time: string) {
    const utcTime = dayjs.utc(dayjs(time)).format('YYYY-MM-DDTHH:mm:00[Z]');
    chooseAction({
      tag: 'ARC_AmuletRules',
      value: {
        amuletRulesAction: {
          tag: 'CRARC_RemoveFutureAmuletConfigSchedule',
          value: { scheduleTime: utcTime },
        },
      },
    });
  }

  return (
    <Stack direction="column" mb={4} spacing={1}>
      <Typography variant="h6">Schedules</Typography>
      <DropdownSchedules
        futureValues={dsoInfosQuery.data.amuletRules.payload.configSchedule.futureValues}
        onChange={RemoveFutureAmuletConfigScheduleAction}
      />
    </Stack>
  );
};

export default RemoveFutureAmuletConfigSchedule;
