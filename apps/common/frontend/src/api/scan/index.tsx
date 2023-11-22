import { ScanClientProvider, useScanClient } from './ScanClientContext';
import useActivity from './useActivity';
import useCoinPrice from './useCoinPrice';
import useGetCnsRules from './useGetCnsRules';
import useGetCoinRules from './useGetCoinRules';
import useGetRoundOfLatestData from './useGetRoundOfLatestData';
import useGetSvcPartyId from './useGetSvcPartyId';
import useGetTopValidatorsByPurchasedTraffic from './useGetTopValidatorsByPurchasedTraffic';
import useGetTopValidatorsByValidatorRewards from './useGetTopValidatorsByValidatorRewards';
import useListCnsEntries from './useListCnsEntries';
import useLookupCnsEntryByName from './useLookupCnsEntryByName';
import useLookupCnsEntryByParty from './useLookupCnsEntryByParty';
import useLookupFeaturedAppRight from './useLookupFeaturedAppRight';
import useTopAppProviders from './useTopAppProviders';
import useTotalCoinBalance from './useTotalCoinBalance';
import useTotalRewards from './useTotalRewards';

export {
  useScanClient,
  ScanClientProvider,
  useCoinPrice,
  useGetCoinRules,
  useGetCnsRules,
  useGetRoundOfLatestData,
  useGetSvcPartyId,
  useGetTopValidatorsByValidatorRewards,
  useGetTopValidatorsByPurchasedTraffic,
  useLookupFeaturedAppRight,
  useActivity,
  useTopAppProviders,
  useTotalCoinBalance,
  useTotalRewards,
  useListCnsEntries,
  useLookupCnsEntryByName,
  useLookupCnsEntryByParty,
};
