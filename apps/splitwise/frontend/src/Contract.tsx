import { ContractId, Template } from '@daml/types';

import { Value } from './com/daml/ledger/api/v1/value_pb';
import { Contract as ProtoContract } from './com/daml/network/v0/contract_pb';

export interface Contract<T> {
  contractId: ContractId<T>;
  payload: T;
}

// eslint-disable-next-line @typescript-eslint/no-redeclare
export const Contract = {
  decode: <T extends object, K>(c: ProtoContract, tmpl: Template<T, K>): Contract<T> => ({
    contractId: c.getContractId() as ContractId<T>,
    payload: tmpl.decodeProto(new Value().setRecord(c.getPayload()!)),
  }),
};
