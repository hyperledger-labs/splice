// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import { Divider, Stack, Typography } from '@mui/material';
import { PropsWithChildren } from 'react';
import { MemberIdentifier } from '../../beta';

interface DetailItemProps extends PropsWithChildren {
  label: string;
  value: React.ReactNode;
  labelId?: string;
  valueId?: string;
  isPartyId?: boolean;
}

export const DetailItem: React.FC<DetailItemProps> = props => {
  const { label, value, labelId, valueId, isPartyId } = props;

  return (
    <Stack gap={3}>
      <Typography
        variant="subtitle2"
        color="white"
        fontWeight="bold"
        fontSize={16}
        lineHeight={1}
        id={labelId}
        data-testid={labelId}
      >
        {label}
      </Typography>
      {isPartyId && typeof value === 'string' ? (
        <MemberIdentifier partyId={value} isYou={false} size="large" data-testid={valueId!} />
      ) : (
        <Typography variant="body1" lineHeight={1} fontSize={16} id={valueId} data-testid={valueId}>
          {value}
        </Typography>
      )}
      <Divider sx={{ borderBottomWidth: 2 }} />
    </Stack>
  );
};
