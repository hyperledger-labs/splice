// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { ConfigChange } from '../components/governance/VoteRequestDetailsContent';
import { Optional } from '@daml/types';
import { AmuletConfig } from '@daml.js/splice-amulet/lib/Splice/AmuletConfig';
import { Tuple2 } from '@daml.js/daml-prim-DA-Types-1.0.0/lib/DA/Types';

function buildTransferSteps(
  before: Tuple2<string, string>[] | undefined,
  after: Tuple2<string, string>[] | undefined
) {
  return (
    before
      ?.map((b, idx) => {
        const a = after?.[idx];
        return [
          {
            fieldName: `Transfer Fee Step ${idx}`,
            currentValue: b._1,
            newValue: a?._1,
          },
          {
            fieldName: `Transfer Fee Step ${idx}`,
            currentValue: b._2,
            newValue: a?._2,
          },
        ] as ConfigChange[];
      })
      .flat() || []
  );
}

export function buildAmuletConfigChanges(
  before: Optional<AmuletConfig<'USD'>>,
  after: Optional<AmuletConfig<'USD'>>
): ConfigChange[] {
  const changes = [
    {
      fieldName: 'Tick Duration (microseconds)',
      currentValue: before?.tickDuration.microseconds || '',
      newValue: after?.tickDuration.microseconds || '',
    },
    {
      fieldName: 'Transfer Preapproval Fee',
      currentValue: before?.transferPreapprovalFee || '',
      newValue: after?.transferPreapprovalFee || '',
    },
    {
      fieldName: 'Featured App Activity Marker Amount',
      currentValue: before?.featuredAppActivityMarkerAmount || '',
      newValue: after?.featuredAppActivityMarkerAmount || '',
    },
    {
      fieldName: 'Package Config (Amulet)',
      currentValue: before?.packageConfig.amulet || '',
      newValue: after?.packageConfig.amulet || '',
    },
    {
      fieldName: 'Package Config (Amulet Name Service)',
      currentValue: before?.packageConfig.amuletNameService || '',
      newValue: after?.packageConfig.amuletNameService || '',
    },
    {
      fieldName: 'Package Config (DSO Governance)',
      currentValue: before?.packageConfig.dsoGovernance || '',
      newValue: after?.packageConfig.dsoGovernance || '',
    },
    {
      fieldName: 'Package Config (Validator Lifecycle)',
      currentValue: before?.packageConfig.validatorLifecycle || '',
      newValue: after?.packageConfig.validatorLifecycle || '',
    },
    {
      fieldName: 'Package Config (Wallet)',
      currentValue: before?.packageConfig.wallet || '',
      newValue: after?.packageConfig.wallet || '',
    },
    {
      fieldName: 'Package Config (Wallet Payments)',
      currentValue: before?.packageConfig.walletPayments || '',
      newValue: after?.packageConfig.walletPayments || '',
    },

    ...buildTransferSteps(
      before?.transferConfig.transferFee?.steps,
      after?.transferConfig.transferFee?.steps
    ),
    // {
    //   fieldName: '',
    //   currentValue: before?.transferConfig.transferFee?.steps,
    //   newValue: '',
    // }
  ] as ConfigChange[];

  return changes.filter(c => c.currentValue !== c.newValue);
}
