// Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { format } from 'date-fns';
import dayjs from 'dayjs';
import relativeTime from 'dayjs/plugin/relativeTime';
import React from 'react';

import { Box } from '@mui/system';

dayjs.extend(relativeTime);

interface DateWithDurationDisplayProps {
  datetime: string | Date | undefined;
  format?: string;
  enableDuration?: boolean;
  onlyDuration?: boolean;
  id?: string;
}

const DateWithDurationDisplay: React.FC<DateWithDurationDisplayProps> = (
  props: DateWithDurationDisplayProps
) => {
  const { datetime, format: customFormat } = props;

  if (!datetime || datetime === 'initial') {
    return <>initial</>;
  }

  const f = customFormat || 'yyyy-MM-dd HH:mm';
  const dateObj = typeof datetime === 'string' ? new Date(datetime) : datetime;

  const expireDuration = props.enableDuration ? `(${dayjs(dateObj).fromNow()})` : '';

  return (
    <Box component="span" id={props.id} data-testid={props.id}>
      {props.enableDuration && props.onlyDuration
        ? expireDuration
        : `${format(dateObj, f)} ${expireDuration}`}
    </Box>
  );
};

export default DateWithDurationDisplay;
