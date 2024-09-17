// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { AssignedContract, Contract } from 'common-frontend-utils/interfaces';

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

export const unitStringToCurrency = (unit: string): string => {
  switch (unit) {
    case 'AMULETUNIT':
      return window.splice_config.spliceInstanceNames?.amuletNameAcronym;
    case 'USDUNIT':
      return 'USD';
    case 'EXTUNIT':
      throw new Error('ExtUnit must not be present at runtime');
    default:
      console.log(`unexpected unit: ${unit}`);
      throw new Error(`Unexpected unit: ${unit}`);
  }
};

export const unitToCurrency = (unit: Unit): string => {
  return unitStringToCurrency(unit.toUpperCase());
};
