import { Numeric, Optional, Party } from '@daml/types';

export interface CoinPriceVote {
  sv: Party;
  coinPrice: Optional<Numeric>;
  lastUpdatedAt: Date;
}
