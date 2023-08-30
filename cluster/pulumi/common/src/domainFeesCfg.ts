// TODO(#6032): determine the best defaults here

// Please keep these values in sync with
//   - domain-fees-overrides.conf
//   - CNNodeUtil.defaultDomainFeesConfig
// TODO(#6322): configure these values in at most one place
export const domainFeesConfig = {
  // Sequencer
  baseRate: 3333, // ~ 100 transactions of 20KB (10 per minute for 10 minutes)
  // Increased to reduce the chance of validators locking up (see #6313)
  maxBurstDuration: '10m',
  // Validator
  targetThroughput: 33330, // 10x the base rate to reduce the chance of validators locking up (see #6313)
  minTopupInterval: '1m',
};
