import { VoteRequest2 } from '@daml.js/svc-governance/lib/CN/SvcRules';
import { ContractId, Numeric, Optional, Party } from '@daml/types';

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
  requestCid: ContractId<VoteRequest2>;
  voter: Party;
  accept: boolean;
  reason: Reason;
  expiresAt: Date;
}
