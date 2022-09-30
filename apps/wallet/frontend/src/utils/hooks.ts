import { useEffect } from 'react';

export const useInterval = (f: () => void, ms: number): void => {
  useEffect(() => {
    const timer = setInterval(f, ms);
    return () => clearInterval(timer);
  }, [f, ms]);
};
