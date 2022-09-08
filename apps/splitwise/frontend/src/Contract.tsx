import { Value } from '@daml/ledger-api';
import { Template } from '@daml/types';

import { Contract as ProtoContract } from './com/daml/network/v0/contract_pb';

export interface Contract<T> {
  contractId: string;
  payload: T;
}

// eslint-disable-next-line @typescript-eslint/no-redeclare
export const Contract = {
  decode: <T extends object, K>(c: ProtoContract, tmpl: Template<T, K>): Contract<T> => ({
    contractId: c.getContractId(),
    payload: tmpl.decodeProto(new Value().setRecord(c.getPayload()!)),
  }),
};
