import dayjs from 'dayjs';
import utc from 'dayjs/plugin/utc';

import { CoinConfig } from '@daml.js/canton-coin/lib/CC/CoinConfig';
import { Schedule } from '@daml.js/canton-coin/lib/CC/Schedule';

dayjs.extend(utc);

export function getCoinConfigurationAsOfNow(
  config: Schedule<string, CoinConfig<'USD'>>
): Schedule<string, CoinConfig<'USD'>> {
  const orderedScheduleList = config.futureValues.sort((a, b) => {
    return new Date(a._1).valueOf() - new Date(b._1).valueOf();
  });

  // if futureValues is empty then return the current configuration
  if (config.futureValues.length === 0) {
    return config;
  }

  const alreadyOccurredSchedules = orderedScheduleList.filter(e =>
    dayjs(e._1).isBefore(dayjs.utc())
  );

  const newConfig: Schedule<string, CoinConfig<'USD'>> = {
    initialValue:
      alreadyOccurredSchedules.length === 0
        ? config.initialValue
        : alreadyOccurredSchedules.pop()!._2,
    futureValues: orderedScheduleList.filter(e => dayjs(e._1).isAfter(dayjs.utc())),
  };

  return newConfig;
}
