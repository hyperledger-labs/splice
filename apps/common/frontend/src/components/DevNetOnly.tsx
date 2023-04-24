import React, { PropsWithChildren, useContext, useEffect, useState } from 'react';

import { useScanClient } from '../contexts';

const IsDevNetContext = React.createContext<boolean>(false);

export const DevNetOnly: React.FC<{ children: React.ReactElement }> = props => {
  const isDevNet = useContext(IsDevNetContext);
  if (!isDevNet) {
    return null;
  } else {
    return props.children;
  }
};

export default DevNetOnly;

export const IsDevNetProvider: React.FC<PropsWithChildren> = ({ children }) => {
  const { getCoinRules } = useScanClient();
  const [isDevNet, setIsDevNet] = useState(false);
  // No need to refresh: it'll stay the same.
  useEffect(() => {
    getCoinRules().then(
      coinRules => setIsDevNet(coinRules.payload.isDevNet),
      err => console.error('Failed to resolve isDevNet', err)
    );
  }, [getCoinRules]);

  return <IsDevNetContext.Provider value={isDevNet}>{children}</IsDevNetContext.Provider>;
};
