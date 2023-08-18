import { Contract } from 'common-frontend';

import { VoteRequest } from '@daml.js/svc-governance/lib/CN/SvcRules/module';

const dayjs = require('dayjs');
const utc = require('dayjs/plugin/utc');
dayjs.extend(utc);

export interface ScheduleValidity {
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
): ScheduleValidity {
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
