// TODO(#6032): determine the best defaults here

export const domainFeesConfig = {
  // Sequencer
  baseRate: 333, // ~ 10 transactions of 20KB (1 per minute for 10 minutes)
  maxBurstDuration: '10m',
  // Validator
  targetThroughput: 10000,
  minTopupInterval: '1m',
};
