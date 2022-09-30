import { Contract } from './interfaces';

export const sameContracts = <T>(a: Contract<T>[], b: Contract<T>[]): boolean => {
  if (a.length !== b.length) {
    return false;
  }
  for (let i = 0; i < a.length; i++) {
    if (a[i].contractId !== b[i].contractId) {
      return false;
    }
  }
  return true;
};
