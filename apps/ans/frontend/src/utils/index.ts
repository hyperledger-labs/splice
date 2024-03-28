export * from './config';

export const ENTRY_NAME_SUFFIX = '.unverified.cns';
export const toFullEntryName: (name: string, suffix: string) => string = (
  name: string,
  suffix: string
) => `${name}${suffix}`;
