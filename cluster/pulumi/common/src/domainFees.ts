export type ValidatorTopupConfig = {
  targetThroughput: number;
  minTopupInterval: string;
};

// TODO(#6032): determine the best defaults here
export const domainFeesConfig = {
  // Validator
  targetThroughput: 33330, // 10x the base rate to reduce the chance of validators locking up (see #6313)
  minTopupInterval: '1m',
};
