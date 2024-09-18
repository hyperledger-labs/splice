// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { format } from 'date-fns';

interface DateDisplayProps {
  datetime: string | Date;
  format?: string; // A valid formatting pattern for date-fns https://date-fns.org/v3.3.1/docs/format
}

const DateDisplay: React.FC<DateDisplayProps> = (props: DateDisplayProps) => {
  const f = props.format || 'MM/dd/yyyy HH:mm';
  const dateObj = typeof props.datetime == 'string' ? new Date(props.datetime) : props.datetime;

  return <>{format(dateObj, f)}</>;
};

export default DateDisplay;
