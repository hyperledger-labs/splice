// Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { microsecondsToInterval } from '@lfdecentralizedtrust/splice-common-frontend-utils/temporal-fns';
import React from 'react';

interface IntervalDisplayProps {
  microseconds: string;
}

const IntervalDisplay: React.FC<IntervalDisplayProps> = (props: IntervalDisplayProps) => (
  <>{microsecondsToInterval(props.microseconds)}</>
);

export default IntervalDisplay;
