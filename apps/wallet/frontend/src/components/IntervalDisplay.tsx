import React from 'react';

import { microsecondsToInterval } from '../utils/temporal-fns';

interface IntervalDisplayProps {
  microseconds: string;
}

const IntervalDisplay: React.FC<IntervalDisplayProps> = (props: IntervalDisplayProps) => (
  <>{microsecondsToInterval(props.microseconds)}</>
);

export { IntervalDisplay };
