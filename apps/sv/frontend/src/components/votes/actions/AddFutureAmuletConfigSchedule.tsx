// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { DateWithDurationDisplay, Loading } from '@lfdecentralizedtrust/splice-common-frontend';
import {
  getUTCWithOffset,
  JsonEditor,
  JSONValue,
} from '@lfdecentralizedtrust/splice-common-frontend-utils';
import { Dayjs } from 'dayjs';
import dayjs from 'dayjs';
import utc from 'dayjs/plugin/utc';
import React, { useEffect, useState } from 'react';

import { FormControl, Stack, Typography } from '@mui/material';
import { DesktopDateTimePicker } from '@mui/x-date-pickers/DesktopDateTimePicker';

import { Tuple2 } from '@daml.js/5aee9b21b8e9a4c4975b5f4c4198e6e6e8469df49e2010820e792f393db870f4/lib/DA/Types';
import { AmuletConfig, USD } from '@daml.js/splice-amulet/lib/Splice/AmuletConfig';

import { useDsoInfos } from '../../../contexts/SvContext';
import { ActionFromForm } from '../VoteRequest';

dayjs.extend(utc);

const AddFutureAmuletConfigSchedule: React.FC<{
  chooseAction: (action: ActionFromForm) => void;
}> = ({ chooseAction }) => {
  const dsoInfosQuery = useDsoInfos();

  const [date, setDate] = useState<Dayjs | null>(dayjs());
  // TODO (#10209): remove this intermediate state by lifting it to VoteRequest.tsx
  const [configuration, setConfiguration] = useState<Record<string, JSONValue>>();

  useEffect(() => {
    if (date != null && configuration != null) {
      const decoded = AmuletConfig(USD).decoder.run(configuration);
      if (decoded.ok) {
        const newItem: Tuple2<string, AmuletConfig<'USD'>> = {
          _1: dayjs.utc(date).format('YYYY-MM-DDTHH:mm:00[Z]'),
          _2: decoded.result,
        };
        chooseAction({
          tag: 'ARC_AmuletRules',
          value: {
            amuletRulesAction: {
              tag: 'CRARC_AddFutureAmuletConfigSchedule',
              value: { newScheduleItem: newItem },
            },
          },
        });
      } else {
        chooseAction({ formError: decoded.error });
      }
    }
  }, [date, configuration, chooseAction]);

  if (dsoInfosQuery.isLoading) {
    return <Loading />;
  }

  if (dsoInfosQuery.isError) {
    return <p>Error: {JSON.stringify(dsoInfosQuery.error)}</p>;
  }

  if (!dsoInfosQuery.data) {
    return <p>undefined query data</p>;
  }

  if (configuration == null) {
    const now = dayjs();
    const configSchedule = dsoInfosQuery.data?.amuletRules.payload.configSchedule;
    const currentConfig = AmuletConfig(USD).encode(
      configSchedule.futureValues.reverse().find(schedule => dayjs(schedule._1).isBefore(now))
        ?._2 || configSchedule.initialValue
    ) as Record<string, JSONValue>;
    setConfiguration(currentConfig);
  }

  function addFutureAmuletConfigScheduleAction(config: Record<string, JSONValue>) {
    setConfiguration(config);
  }

  return (
    <Stack direction="column" mb={4} spacing={1}>
      <Typography variant="h6" mt={4}>
        Configuration Effective Date
      </Typography>
      <FormControl sx={{ marginRight: '32px', flexGrow: '1' }}>
        <DesktopDateTimePicker
          label={`Enter time in local timezone (${getUTCWithOffset()})`}
          value={date}
          minDateTime={dayjs()}
          ampm={false}
          format="YYYY-MM-DD HH:mm"
          readOnly={false}
          onChange={(newValue: Dayjs | null) => {
            try {
              newValue?.toISOString();
              setDate(newValue);
            } catch (error) {
              console.log('Invalid date', error);
              return;
            }
          }}
          slotProps={{
            textField: {
              id: 'datetime-picker-amulet-configuration',
            },
          }}
          closeOnSelect
        />
        <Typography variant="body2" mt={1}>
          Effective{' '}
          <DateWithDurationDisplay datetime={date?.toDate()} enableDuration onlyDuration />
        </Typography>
        <Typography variant="h6" mt={4}>
          Configuration
        </Typography>
        <JsonEditor data={configuration!} onChange={addFutureAmuletConfigScheduleAction} />
      </FormControl>
    </Stack>
  );
};

export default AddFutureAmuletConfigSchedule;
