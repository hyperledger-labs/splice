// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { format } from 'date-fns';
import dayjs from 'dayjs';
import relativeTime from 'dayjs/plugin/relativeTime';
import React from 'react';

dayjs.extend(relativeTime);

interface DateWithDurationDisplayProps {
  datetime: string | Date | undefined;
  format?: string;
  enableDuration?: boolean;
}

const DateWithDurationDisplay: React.FC<DateWithDurationDisplayProps> = (
  props: DateWithDurationDisplayProps
) => {
  const { datetime, format: customFormat } = props;

  if (!datetime || datetime === 'initial') {
    return <>initial</>;
  }

  const f = customFormat || 'PPp O'; // Use custom format if provided, or default to 'PPp O'
  const dateObj = typeof datetime === 'string' ? new Date(datetime) : datetime;

  const expireDuration = props.enableDuration ? `(${dayjs(dateObj).fromNow()})` : '';

  return (
    <>
      {format(dateObj, f)} <span>{expireDuration}</span>
    </>
  );
};

export default DateWithDurationDisplay;
