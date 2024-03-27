export type DomainFeesConfig = {
  extraTrafficPrice: number;
  minTopupAmount: number;
  baseRateBurstAmount: number;
  baseRateBurstWindowMins: number;
  readVsWriteScalingFactor: number;
};

// default values for domain fees parameters within our clusters.
// These should generally be kept in sync with the values defined in the DomainFeesConfig in SvAppConfig.scala
const domainFeesDefaults: DomainFeesConfig = {
  extraTrafficPrice: 1.0,
  minTopupAmount: 10_000_000,
  baseRateBurstAmount: 100 * 20 * 1000,
  baseRateBurstWindowMins: 10,
  readVsWriteScalingFactor: 200,
};

function parseNumFromEnv(envVar: string, otherwise: number): number {
  const val = parseFloat(process.env[envVar] || '');
  return isNaN(val) ? otherwise : val;
}

export const initialDomainFeesConfig: DomainFeesConfig = {
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
};

export const svValidatorTopupConfig: ValidatorTopupConfig = {
  targetThroughput: 0,
  minTopupInterval: '1m',
};

export const nonSvValidatorTopupConfig: ValidatorTopupConfig = {
  targetThroughput: 33330, // 10x the base rate to reduce the chance of validators locking up (see #6313)
  minTopupInterval: '1m',
};
