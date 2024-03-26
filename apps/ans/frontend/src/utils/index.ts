export * from './config';

export const ENTRY_NAME_SUFFIX = '.unverified.ans';
export const toFullEntryName: (name: string, suffix: string) => string = (
  name: string,
  suffix: string
) => `${name}${suffix}`;
