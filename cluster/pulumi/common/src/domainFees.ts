// Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { config } from './config';

export type SynchronizerFeesConfig = {
  extraTrafficPrice: number;
  minTopupAmount: number;
  baseRateBurstAmount: number;
  baseRateBurstWindowMins: number;
  readVsWriteScalingFactor: number;
};

// default values for domain fees parameters within our clusters,
// assuming a network with 4-6 SVs and formula(s) outlined in #11286
// These should generally be kept in sync with the values defined in the SynchronizerFeesConfig in SvAppConfig.scala
const domainFeesDefaults: SynchronizerFeesConfig = {
  extraTrafficPrice: 16.67,
  minTopupAmount: 200_000,
  baseRateBurstAmount: 200_000,
  baseRateBurstWindowMins: 20,
  readVsWriteScalingFactor: 4,
};

function parseNumFromEnv(envVar: string, otherwise: number): number {
  const val = parseFloat(config.optionalEnv(envVar) || '');
  return isNaN(val) ? otherwise : val;
}

export const initialSynchronizerFeesConfig: SynchronizerFeesConfig = {
  extraTrafficPrice: parseNumFromEnv(
    'DOMAIN_FEES_EXTRA_TRAFFIC_PRICE',
    domainFeesDefaults.extraTrafficPrice
  ),
  minTopupAmount: parseNumFromEnv(
    'DOMAIN_FEES_MIN_TOPUP_AMOUNT',
    domainFeesDefaults.minTopupAmount
  ),
  baseRateBurstAmount: parseNumFromEnv(
    'DOMAIN_FEES_BASE_RATE_BURST_AMOUNT',
    domainFeesDefaults.baseRateBurstAmount
  ),
  baseRateBurstWindowMins: parseNumFromEnv(
    'DOMAIN_FEES_BASE_RATE_BURST_WINDOW_MINS',
    domainFeesDefaults.baseRateBurstWindowMins
  ),
  readVsWriteScalingFactor: parseNumFromEnv(
    'DOMAIN_FEES_READ_VS_WRITE_SCALING_FACTOR',
    domainFeesDefaults.readVsWriteScalingFactor
  ),
};

export type ValidatorTopupConfig = {
  targetThroughput: number;
  minTopupInterval: string;
  reservedTraffic?: number;
};

export const svValidatorTopupConfig: ValidatorTopupConfig = {
  targetThroughput: 0,
  minTopupInterval: '1m',
};

export const nonSvValidatorTopupConfig: ValidatorTopupConfig = {
  targetThroughput: 100000,
  minTopupInterval: '1m',
};
// Configure target throughput such that a validator is able to top-up within 2-3 rounds on non-DevNet clusters.
// Redeeming faucet coupons earns each validator 564CC each round.
// Given the amulet config as of this writing and with the topup config set here, a validator would require
// (4500 * 60 / 10^6)MB * 20$/MB / 0.005$/CC = 1080CC for each top-up.
export const nonDevNetNonSvValidatorTopupConfig: ValidatorTopupConfig = {
  targetThroughput: 4500,
  minTopupInterval: '1m',
};
