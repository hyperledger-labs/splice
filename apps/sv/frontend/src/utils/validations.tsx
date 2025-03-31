// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { Contract } from '@lfdecentralizedtrust/splice-common-frontend-utils';
import { Dayjs } from 'dayjs';
import dayjs from 'dayjs';
import utc from 'dayjs/plugin/utc';

import { VoteRequest } from '@daml.js/splice-dso-governance/lib/Splice/DsoRules/module';

dayjs.extend(utc);

export interface VoteRequestValidity {
  isValid: boolean;
  alertMessage: object;
}

function validateScheduleDateTime(
  data: Contract<VoteRequest>[],
  formattedUTCtime: string
): boolean {
  const takenTimes =
    data.map(e => {
      switch (e.payload.action.tag) {
        case 'ARC_AmuletRules':
          const amuletRulesAction = e.payload.action.value.amuletRulesAction;
          switch (amuletRulesAction.tag) {
            case 'CRARC_RemoveFutureAmuletConfigSchedule': {
              return amuletRulesAction.value.scheduleTime;
            }
            case 'CRARC_UpdateFutureAmuletConfigSchedule': {
              return amuletRulesAction.value.scheduleItem._1;
            }
            case 'CRARC_AddFutureAmuletConfigSchedule': {
              return amuletRulesAction.value.newScheduleItem._1;
            }
            default:
              return '';
          }
        default:
          return '';
      }
    }) || [];
  return !takenTimes.includes(formattedUTCtime);
}

export function isScheduleDateTimeValid(
  data: Contract<VoteRequest>[],
  time: string
): VoteRequestValidity {
  if (validateScheduleDateTime(data, time)) {
    return { isValid: true, alertMessage: {} };
  } else {
    return {
      isValid: false,
      alertMessage: {
        severity: 'warning',
        message: `Another vote request for a schedule adjustment at ${time} is already on record. Please change the scheduled time to continue.`,
      },
    };
  }
}

export function isExpirationBeforeEffectiveDate(
  effectiveTime: Dayjs,
  expiration: Dayjs | null
): VoteRequestValidity {
  if (expiration === null) {
    return {
      isValid: true,
      alertMessage: {},
    };
  } else if (
    effectiveTime.isAfter(expiration) &&
    expiration.isAfter(dayjs()) &&
    effectiveTime.isAfter(dayjs())
  ) {
    return {
      isValid: true,
      alertMessage: {},
    };
  } else {
    return {
      isValid: false,
      alertMessage: {
        severity: 'warning',
        message:
          'The expiration date must be before the effective date of the configuration schedule and both dates must be in the future.',
      },
    };
  }
}

export function isValidUrl(url: string): boolean {
  let validUrl: URL;
  try {
    validUrl = new URL(url);
  } catch (error) {
    return false;
  }
  return ['http:', 'https:'].some(protocol => validUrl.protocol === protocol);
}

export function isValidVoteRequestUrl(url: string): boolean {
  return url === '' || isValidUrl(url);
}
