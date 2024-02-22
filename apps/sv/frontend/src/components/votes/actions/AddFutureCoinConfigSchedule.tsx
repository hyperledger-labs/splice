import { Loading } from 'common-frontend';
import { getUTCWithOffset, JsonEditor, JSONValue } from 'common-frontend-utils';
import { Dayjs } from 'dayjs';
import dayjs from 'dayjs';
import utc from 'dayjs/plugin/utc';
import React, { useEffect, useState } from 'react';

import { FormControl, Stack, Typography } from '@mui/material';
import { DesktopDateTimePicker } from '@mui/x-date-pickers/DesktopDateTimePicker';

import { Tuple2 } from '@daml.js/87530dd1038863bad7bdf02c59ae851bc00f469edb2d7dbc8be3172daafa638c/lib/DA/Types';
import { CoinConfig, USD } from '@daml.js/canton-coin/lib/CC/CoinConfig';

import { useSvcInfos } from '../../../contexts/SvContext';
import { ActionFromForm } from '../VoteRequest';

dayjs.extend(utc);

const AddFutureCoinConfigSchedule: React.FC<{
  chooseAction: (action: ActionFromForm) => void;
}> = ({ chooseAction }) => {
  const svcInfosQuery = useSvcInfos();

  const [date, setDate] = useState<Dayjs | null>(dayjs());
  // TODO (#10209): remove this intermediate state by lifting it to VoteRequest.tsx
  const [configuration, setConfiguration] = useState<Record<string, JSONValue>>();

  useEffect(() => {
    if (date != null && configuration != null) {
      const decoded = CoinConfig(USD).decoder.run(configuration);
      if (decoded.ok) {
        const newItem: Tuple2<string, CoinConfig<'USD'>> = {
          _1: dayjs.utc(date).format('YYYY-MM-DDTHH:mm:00[Z]'),
          _2: decoded.result,
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
      } else {
        chooseAction({ formError: decoded.error });
      }
    }
  }, [date, configuration, chooseAction]);

  if (svcInfosQuery.isLoading) {
    return <Loading />;
  }

  if (svcInfosQuery.isError) {
    return <p>Error: {JSON.stringify(svcInfosQuery.error)}</p>;
  }

  if (!svcInfosQuery.data) {
    return <p>undefined query data</p>;
  }

  if (configuration == null) {
    const originalConfig = CoinConfig(USD).encode(
      svcInfosQuery.data?.coinRules.payload.configSchedule.initialValue!
    ) as Record<string, JSONValue>;
    setConfiguration(originalConfig);
  }

  function addFutureCoinConfigScheduleAction(config: Record<string, JSONValue>) {
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
          readOnly={false}
          onChange={(newValue: Dayjs | null) => setDate(newValue)}
          slotProps={{
            textField: {
              id: 'datetime-picker-coin-configuration',
            },
          }}
          closeOnSelect
        />
        <Typography variant="h6" mt={4}>
          Configuration
        </Typography>
        <JsonEditor data={configuration!} onChange={addFutureCoinConfigScheduleAction} />
      </FormControl>
    </Stack>
  );
};

export default AddFutureCoinConfigSchedule;
