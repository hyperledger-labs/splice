// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import dayjs from 'dayjs';
import utc from 'dayjs/plugin/utc';

import { AmuletConfig } from '@daml.js/splice-amulet/lib/Splice/AmuletConfig';
import { Schedule } from '@daml.js/splice-amulet/lib/Splice/Schedule';

dayjs.extend(utc);

export function getAmuletConfigurationAsOfNow(
  config: Schedule<string, AmuletConfig<'USD'>>
): Schedule<string, AmuletConfig<'USD'>> {
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

  const newConfig: Schedule<string, AmuletConfig<'USD'>> = {
    initialValue:
      alreadyOccurredSchedules.length === 0
        ? config.initialValue
        : alreadyOccurredSchedules.pop()!._2,
    futureValues: orderedScheduleList.filter(e => dayjs(e._1).isAfter(dayjs.utc())),
  };

  return newConfig;
}
