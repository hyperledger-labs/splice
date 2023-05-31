import { ContractId, Numeric, Optional, Party } from '@daml/types';

import {
  Vote,
  VoteRequest,
} from '../../../../common/frontend/daml.js/svc-governance-0.1.0/lib/CN/SvcRules';

export interface CoinPriceVote {
  sv: Party;
  coinPrice: Optional<Numeric>;
  lastUpdatedAt: Date;
}

export interface Reason {
  url: string;
  body: string;
}

export interface SvVote {
  contractId: ContractId<Vote>;
  requestCid: ContractId<VoteRequest>;
  voter: Party;
  accept: boolean;
  reason: Reason;
  expiresAt: Date;
}
