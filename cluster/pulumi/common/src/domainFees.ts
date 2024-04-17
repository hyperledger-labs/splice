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
  extraTrafficPrice: 20.0,
  minTopupAmount: 165_000,
  baseRateBurstAmount: 165_000,
  baseRateBurstWindowMins: 20,
  readVsWriteScalingFactor: 200,
};

function parseNumFromEnv(envVar: string, otherwise: number): number {
  const val = parseFloat(process.env[envVar] || '');
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
  trafficReservedForTopups?: number;
};

export const svValidatorTopupConfig: ValidatorTopupConfig = {
  targetThroughput: 0,
  minTopupInterval: '1m',
};

export const nonSvValidatorTopupConfig: ValidatorTopupConfig = {
  // TODO(#11377): Determine the best defaults here
  targetThroughput: 20000,
  minTopupInterval: '1m',
};
