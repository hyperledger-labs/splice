import { Contract } from 'common-frontend';
import { Dayjs } from 'dayjs';
import dayjs from 'dayjs';
import utc from 'dayjs/plugin/utc';

import { VoteRequest } from '@daml.js/svc-governance/lib/CN/SvcRules/module';

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
        case 'ARC_CoinRules':
          const coinRulesAction = e.payload.action.value.coinRulesAction;
          switch (coinRulesAction.tag) {
            case 'CRARC_RemoveFutureCoinConfigSchedule': {
              return coinRulesAction.value.scheduleTime;
            }
            case 'CRARC_UpdateFutureCoinConfigSchedule': {
              return coinRulesAction.value.scheduleItem._1;
            }
            case 'CRARC_AddFutureCoinConfigSchedule': {
              return coinRulesAction.value.newScheduleItem._1;
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
