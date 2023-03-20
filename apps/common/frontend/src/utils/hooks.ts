import { useEffect } from 'react';

const DEFAULT_INTERVAL_MS = 500;

export const useInterval = (f: () => void, ms: number = DEFAULT_INTERVAL_MS): void => {
  useEffect(() => {
    const timer = setInterval(f, ms);
    return () => clearInterval(timer);
  }, [f, ms]);
};
