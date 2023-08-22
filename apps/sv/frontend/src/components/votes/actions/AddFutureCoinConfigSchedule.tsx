import { getUTCWithOffset, JsonEditor, JSONValue, Loading } from 'common-frontend';
import { Dayjs } from 'dayjs';
import React, { useEffect, useState } from 'react';

import { FormControl, Stack, Typography } from '@mui/material';
import { DesktopDateTimePicker } from '@mui/x-date-pickers/DesktopDateTimePicker';

import { Tuple2 } from '@daml.js/40f452260bef3f29dede136108fc08a88d5a5250310281067087da6f0baddff7/lib/DA/Types';
import { CoinConfig, USD } from '@daml.js/canton-coin-0.1.0/lib/CC/CoinConfig';
import { ActionRequiringConfirmation } from '@daml.js/svc-governance/lib/CN/SvcRules/module';

import { useSvcInfos } from '../../../contexts/SvContext';

const dayjs = require('dayjs');
const utc = require('dayjs/plugin/utc');
dayjs.extend(utc);

const AddFutureCoinConfigSchedule: React.FC<{
  chooseAction: (action: ActionRequiringConfirmation) => void;
}> = ({ chooseAction }) => {
  const svcInfosQuery = useSvcInfos();

  const [date, setDate] = useState<Dayjs | null>(dayjs());
  const [configuration, setConfiguration] = useState<Record<string, JSONValue>>();

  useEffect(() => {
    if (date != null && configuration != null) {
      const newItem: Tuple2<string, CoinConfig<'USD'>> = {
        _1: dayjs.utc(date).format('YYYY-MM-DDTHH:mm:00[Z]'),
        _2: CoinConfig(USD).decoder.runWithException(configuration),
      };
      chooseAction({
        tag: 'ARC_CoinRules',
        value: {
          coinRulesAction: {
            tag: 'CRARC_AddFutureCoinConfigSchedule',
            value: { newScheduleItem: newItem },
          },
        },
      });
    }
  }, [date, configuration, chooseAction]);

  if (svcInfosQuery.isLoading) {
    return <Loading />;
  }

  if (svcInfosQuery.isError) {
    return <p>Not yet implemented.</p>;
  }

  if (!svcInfosQuery.data) {
    return <p>undefined query data</p>;
  }

  function addFutureCoinConfigScheduleAction(config: Record<string, JSONValue>) {
    setConfiguration(config);
  }

  return (
    <Stack direction="column" mb={4} spacing={1}>
      <Typography variant="h6">Configuration</Typography>
      <FormControl sx={{ marginRight: '32px', flexGrow: '1' }}>
        <DesktopDateTimePicker
          label={`Enter time in local timezone (${getUTCWithOffset()})`}
          value={date}
          minDateTime={dayjs()}
          readOnly={false}
          onChange={(newValue: Dayjs | null) => setDate(newValue)}
          slotProps={{
            textField: {
              id: 'datetime-picker-coin-configuration',
            },
          }}
          closeOnSelect
        />
        <JsonEditor
          data={
            CoinConfig(USD).encode(
              svcInfosQuery.data?.coinRules.payload.configSchedule.initialValue!
            ) as Record<string, JSONValue>
          }
          onChange={addFutureCoinConfigScheduleAction}
        />
      </FormControl>
    </Stack>
  );
};

export default AddFutureCoinConfigSchedule;
