// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { format } from 'date-fns';

import { Box } from '@mui/material';

interface DateDisplayProps {
  datetime: string | Date;
  format?: string; // A valid formatting pattern for date-fns https://date-fns.org/v3.3.1/docs/format
  id?: string;
}

const DateDisplay: React.FC<DateDisplayProps> = (props: DateDisplayProps) => {
  const f = props.format || 'yyyy-MM-dd HH:mm';
  const dateObj = typeof props.datetime == 'string' ? new Date(props.datetime) : props.datetime;

  return (
    <Box component="span" id={props.id} data-testid={props.id}>
      {format(dateObj, f)}
    </Box>
  );
};

export default DateDisplay;
