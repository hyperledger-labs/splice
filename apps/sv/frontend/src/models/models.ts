import { VoteRequest } from '@daml.js/dso-governance/lib/Splice/DsoRules';
import { ContractId, Numeric, Optional, Party } from '@daml/types';

export interface AmuletPriceVote {
  sv: Party;
  amuletPrice: Optional<Numeric>;
  lastUpdatedAt: Date;
}

export interface Reason {
  url: string;
  body: string;
}

export interface SvVote {
  requestCid: ContractId<VoteRequest>;
  voter: Party;
  accept: boolean;
  reason: Reason;
  expiresAt: Date;
}
