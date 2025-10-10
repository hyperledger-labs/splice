// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { Temporal } from '@js-temporal/polyfill';
import BigNumber from 'bignumber.js';

function prettyPrintInterval(
  seconds: number = 0,
  minutes: number = 0,
  hours: number = 0,
  days: number = 0,
  months: number = 0,
  years: number = 0
) {
  const timeUnits = [
    { unit: 'year', value: years },
    { unit: 'month', value: months },
    { unit: 'day', value: days },
    { unit: 'hour', value: hours },
    { unit: 'minute', value: minutes },
    { unit: 'second', value: seconds },
  ];

  const formatDuration = (unit: string, value: number) =>
    `${value} ${unit}${value === 1 ? '' : 's'}`;

  const durations = timeUnits
    .filter(({ value }) => value > 0)
    .map(({ unit, value }) => formatDuration(unit, value));

  return durations.join(', ');
}

export const dateTimeFormatISO = 'YYYY-MM-DD HH:mm';
export const nextScheduledSynchronizerUpgradeFormat = 'YYYY-MM-DDTHH:mm:ss[Z]';

export function durationToInterval(duration: string): string {
  const { years, months, days, hours, minutes, seconds } = Temporal.Duration.from(duration);

  return prettyPrintInterval(seconds, minutes, hours, days, months, years);
}

export const microsecondsToInterval: (microseconds: string) => string = (microseconds: string) => {
  const second = new BigNumber(microseconds).div(1000000).toNumber();

  const days = Math.floor(second / (24 * 3600));
  const hours = Math.floor((second % (3600 * 24)) / 3600);
  const minutes = Math.floor((second % 3600) / 60);
  const seconds = Math.floor((second % 3600) % 60);

  return prettyPrintInterval(seconds, minutes, hours, days);
};

export const microsecondsToSeconds = (microseconds: string): BigNumber =>
  new BigNumber(microseconds).div(1000000);

export const microsecondsToMinutes = (microseconds: string): BigNumber =>
  microsecondsToSeconds(microseconds).div(60);

export interface FormattedDateTime {
  date: string;
  time: string;
}

export const formatDatetime: (datetime: string | Date) => FormattedDateTime = (
  datetime: string | Date
) => {
  const dateObj = typeof datetime == 'string' ? new Date(datetime) : datetime;
  return {
    date: dateObj.toLocaleDateString('en-US', {
      day: '2-digit',
      month: '2-digit',
      year: 'numeric',
    }),
    time: dateObj.toLocaleTimeString('en-US', {
      hour: '2-digit',
      minute: '2-digit',
      hour12: false,
    }),
  };
};

export function getUTCWithOffset(): string {
  const dt = new Date();
  const timezoneOffset = dt.getTimezoneOffset();

  const offsetHours = Math.floor(Math.abs(timezoneOffset) / 60.0);
  const offsetMinutes = Math.abs(timezoneOffset) % 60;
  const offsetSign = timezoneOffset < 0 ? '+' : '-';

  return `UTC${offsetSign}${offsetHours.toString().padStart(2, '0')}:${offsetMinutes
    .toString()
    .padStart(2, '0')}`;
}
