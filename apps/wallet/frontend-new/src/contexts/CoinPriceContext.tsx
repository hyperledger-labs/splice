import BigNumber from 'bignumber.js';
import { useInterval, useScanClient } from 'common-frontend';
import { createContext, useCallback, useContext, useState } from 'react';

type CoinPrice = BigNumber | undefined;

const CoinPriceContext: React.Context<CoinPrice> = createContext<CoinPrice>(undefined);

export const CoinPriceProvider: React.FC<React.PropsWithChildren> = ({ children }) => {
  const { getCoinPrice } = useScanClient();
  const [coinPrice, setCoinPrice] = useState<CoinPrice>(undefined);

  const fetchCoinPrice = useCallback(async () => {
    const coinPrice = await getCoinPrice();
    setCoinPrice(coinPrice);
  }, [getCoinPrice]);

  useInterval(fetchCoinPrice, 2000); // TODO (#3434): factor out

  return <CoinPriceContext.Provider value={coinPrice}>{children}</CoinPriceContext.Provider>;
};

/** Returns the coin price, as USD/CC.
 */
export const useCoinPrice: () => CoinPrice = () => {
  return useContext(CoinPriceContext);
};
