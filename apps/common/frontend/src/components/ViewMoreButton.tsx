// Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as React from 'react';

import { Button } from '@mui/material';

interface ViewMoreButtonProps {
  loadMore: () => void;
  label: string;
  disabled: boolean;
  idSuffix: string;
}
export const ViewMoreButton: React.FC<ViewMoreButtonProps> = ({
  loadMore,
  label,
  disabled,
  idSuffix,
}) => {
  return (
    <Button
      id={`view-more-${idSuffix}`}
      variant="outlined"
      size="small"
      color="secondary"
      onClick={loadMore}
      disabled={disabled}
    >
      {label}
    </Button>
  );
};

export default ViewMoreButton;
