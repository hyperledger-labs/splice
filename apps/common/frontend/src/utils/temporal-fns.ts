import BigNumber from 'bignumber.js';

export const microsecondsToInterval: (microseconds: string) => string = (microseconds: string) => {
  const seconds = new BigNumber(microseconds).div(1000000).toNumber();

  const d = Math.floor(seconds / (24 * 3600));
  const h = Math.floor((seconds % (3600 * 24)) / 3600);
  const m = Math.floor((seconds % 3600) / 60);
  const s = Math.floor((seconds % 3600) % 60);

  const days =
    d > 0 ? d + (d === 1 ? ' day' : ' days') + (h > 0 || m > 0 || s > 0 ? ', ' : '') : '';
  const hours = h > 0 ? h + (h === 1 ? ' hr' : ' hrs') + (m > 0 || s > 0 ? ', ' : '') : '';
  const mins = m > 0 ? m + (m === 1 ? ' min' : ' mins') + (s > 0 ? ', ' : '') : '';
  const secs = s > 0 ? s + (s === 1 ? ' sec' : ' secs') : '';
  return days + hours + mins + secs;
};

export const microsecondsToSeconds = (microseconds: string): BigNumber =>
  new BigNumber(microseconds).div(1000000);

export const microsecondsToMinutes = (microseconds: string): BigNumber =>
  microsecondsToSeconds(microseconds).div(60);

export interface FormattedDateTime {
  date: string;
  time: string;
}

export const formatDatetime: (datetime: string) => FormattedDateTime = (datetime: string) => {
  const dateObj = new Date(datetime);
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
