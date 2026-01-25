// Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as React from 'react';

import { AlertColor, Stack } from '@mui/material';
import Tooltip from '@mui/material/Tooltip';

import { Alerting } from './Alerting';

type DisableConditionallyProps = {
  conditions: { disabled: boolean; reason: string; severity?: AlertColor }[];
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  children: React.ReactElement<{ disabled: boolean }, any>;
};

/** A wrapper that disables the enclosed element, and forces callers to provide a reason why it was disabled.
 * The reason is rendered both as a tooltip and as an accessible description,
 * see https://mui.com/material-ui/react-tooltip/#accessibility.
 *
 * Usage:
 * ```
 * <DisableConditionally
 *  conditions={[
 *     {
 *       disabled: isLoading,
 *       reason: "Submitting...",
 *     },
 *     {
 *       disabled: hasSubmitted
 *       reason: `Party has already submitted`,
 *     },
 *   ]}
 * >
 *     <Button>Click me</Button>
 * <DisableConditionally>
 * ```
 * */
const DisableConditionally: React.FC<DisableConditionallyProps> = props => {
  const condition = props.conditions.find(c => c.disabled);
  if (condition !== undefined) {
    if (!React.isValidElement(props.children)) {
      throw new Error('DisableConditionally: children must be a valid React element');
    }
    const disabledChild = React.cloneElement(props.children, { disabled: true });
    return (
      <Stack>
        {condition.severity && (
          <Alerting alertState={{ severity: condition.severity, message: condition.reason }} />
        )}
        <Tooltip describeChild title={condition.reason}>
          {/*Span is needed to make the tooltip work with disabled elements, see https://mui.com/material-ui/react-tooltip/#disabled-elements*/}
          <span>{disabledChild}</span>
        </Tooltip>
      </Stack>
    );
  } else {
    return props.children;
  }
};

export default DisableConditionally;
