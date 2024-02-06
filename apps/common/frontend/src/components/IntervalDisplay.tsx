import { microsecondsToInterval } from 'common-frontend-utils/temporal-fns';
import React from 'react';

interface IntervalDisplayProps {
  microseconds: string;
}

const IntervalDisplay: React.FC<IntervalDisplayProps> = (props: IntervalDisplayProps) => (
  <>{microsecondsToInterval(props.microseconds)}</>
);

export default IntervalDisplay;
