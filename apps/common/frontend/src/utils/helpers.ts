import { Contract, AssignedContract } from 'common-frontend-utils/interfaces';

import { Unit } from '@daml.js/splice-wallet-payments/lib/Splice/Wallet/Payment';

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

export function sameAssignedContracts<T>(
  a: AssignedContract<T>[],
  b: AssignedContract<T>[]
): boolean {
  return equalWith(
    a,
    b,
    (l, r) => l.contract.contractId === r.contract.contractId && l.domainId === r.domainId
  );
}

export type Currency = 'CC' | 'USD';

// TODO(#11265) Clean this up
export const unitStringToCurrency = (unit: string): Currency => {
  switch (unit) {
    case 'AMULETUNIT':
      return 'CC';
    case 'USDUNIT':
      return 'USD';
    case 'EXTUNIT':
      throw new Error('ExtUnit must not be present at runtime');
    default:
      console.log(`unexpected unit: ${unit}`);
      throw new Error(`Unexpected unit: ${unit}`);
  }
};

export const unitToCurrency = (unit: Unit): Currency => {
  return unitStringToCurrency(unit.toUpperCase());
};
