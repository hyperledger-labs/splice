// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { microsecondsToInterval } from 'common-frontend-utils/temporal-fns';
import React from 'react';

interface IntervalDisplayProps {
  microseconds: string;
}

const IntervalDisplay: React.FC<IntervalDisplayProps> = (props: IntervalDisplayProps) => (
  <>{microsecondsToInterval(props.microseconds)}</>
);

export default IntervalDisplay;
