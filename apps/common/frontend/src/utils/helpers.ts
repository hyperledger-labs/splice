import { Contract, ReadyContract } from './interfaces';

function equalWith<T>(a: T[], b: T[], p: (a: T, b: T) => boolean) {
  if (a.length !== b.length) {
    return false;
  }
  for (let i = 0; i < a.length; i++) {
    if (!p(a[i], b[i])) {
      return false;
    }
  }
  return true;
}

export const sameContracts = <T>(a: Contract<T>[], b: Contract<T>[]): boolean => {
  return equalWith(a, b, (l, r) => l.contractId === r.contractId);
};

export function sameReadyContracts<T>(a: ReadyContract<T>[], b: ReadyContract<T>[]): boolean {
  return equalWith(
    a,
    b,
    (l, r) => l.contract.contractId === r.contract.contractId && l.domainId === r.domainId
  );
}
