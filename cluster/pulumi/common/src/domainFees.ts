export type ValidatorTopupConfig = {
  targetThroughput: number;
  minTopupInterval: string;
};

// TODO(#6032): determine the best defaults here
export const svValidatorTopupConfig: ValidatorTopupConfig = {
  targetThroughput: 0,
  minTopupInterval: '1m',
};

export const nonSvValidatorTopupConfig: ValidatorTopupConfig = {
  targetThroughput: 33330, // 10x the base rate to reduce the chance of validators locking up (see #6313)
  minTopupInterval: '1m',
};
