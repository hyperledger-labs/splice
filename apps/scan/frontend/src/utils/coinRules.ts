import { Contract } from 'common-frontend';
import { CoinRules } from 'common-frontend/daml.js/canton-coin-0.1.0/lib/CC/Coin/module';
import { CoinConfig } from 'common-frontend/daml.js/canton-coin-0.1.0/lib/CC/CoinConfig/module';
import { isAfter, isDate, isFuture, min } from 'date-fns';

export function getLatestActiveCoinConfig(coinRules: Contract<CoinRules>): CoinConfig<'USD'> {
  const { futureValues, initialValue } = coinRules.payload.configSchedule;

  if (futureValues.length === 0) {
    return initialValue;
  }

  if (!futureValues.map(val => val._1).every(isDate)) {
    throw new Error('Received invalid dates in futureValues');
  }

  // Get back the latest past value
  return futureValues.reduce<[Date, CoinConfig<'USD'>]>(
    (latestValue, futureValue) => {
      const futureValueDate = new Date(futureValue._1);
      const shouldPickFutureValue =
        isAfter(futureValueDate, latestValue[0]) && !isFuture(futureValueDate);

      return shouldPickFutureValue ? [new Date(futureValue._1), futureValue._2] : latestValue;
    },
    [new Date(0), initialValue]
  )[1];
}

export function getNextCoinConfigUpdateTime(coinRules: Contract<CoinRules>): Date | undefined {
  const futureValueTimes = coinRules.payload.configSchedule.futureValues.map(val => val._1);

  if (futureValueTimes.length === 0) {
    return undefined;
  }

  if (!futureValueTimes.every(isDate)) {
    throw new Error('Received invalid dates in futureValues');
  }

  // Get back the earliest future value
  return min(futureValueTimes.map(ts => new Date(ts)).filter(isFuture));
}
