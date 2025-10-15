// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import {
  AuthProvider,
  SvClientProvider,
  theme,
  UserProvider,
} from '@lfdecentralizedtrust/splice-common-frontend';
import { replaceEqualDeep } from '@lfdecentralizedtrust/splice-common-frontend-utils';
import { ThemeProvider } from '@mui/material';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, useNavigate } from 'react-router-dom';
import { expect } from 'vitest';
import { SvAdminClientProvider } from '../contexts/SvAdminServiceContext';
import { SvAppVotesHooksProvider } from '../contexts/SvAppVotesHooksContext';
import { SvConfigProvider, useSvConfig } from '../utils';

const testQueryClient = new QueryClient({
  defaultOptions: {
    queries: {
      refetchInterval: 500,
      structuralSharing: replaceEqualDeep,
      retry: false,
      gcTime: 0,
    },
  },
});

const WrapperProviders: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const config = useSvConfig();
  const navigate = useNavigate();

  return (
    <ThemeProvider theme={theme}>
      <AuthProvider authConf={config.auth} redirect={(path: string) => navigate(path)}>
        <QueryClientProvider client={testQueryClient}>
          <UserProvider authConf={config.auth} testAuthConf={config.testAuth}>
            <SvClientProvider url={config.services.sv.url}>
              <SvAppVotesHooksProvider>
                <SvAdminClientProvider url={config.services.sv.url}>
                  {children}
                </SvAdminClientProvider>
              </SvAppVotesHooksProvider>
            </SvClientProvider>
          </UserProvider>
        </QueryClientProvider>
      </AuthProvider>
    </ThemeProvider>
  );
};

export const Wrapper: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  return (
    <MemoryRouter>
      <SvConfigProvider>
        <WrapperProviders children={children} />
      </SvConfigProvider>
    </MemoryRouter>
  );
};

// Strong type for syntax hints
type ActionName =
  | 'SRARC_OffboardSv'
  | 'SRARC_GrantFeaturedAppRight'
  | 'SRARC_RevokeFeaturedAppRight'
  | 'SRARC_SetConfig'
  | 'CRARC_SetConfig'
  | 'SRARC_UpdateSvRewardWeight'
  | 'SRARC_CreateUnallocatedUnclaimedActivityRecord'
  | (string & {});

export async function changeAction(actionName: ActionName = 'SRARC_SetConfig'): Promise<void> {
  const dropdown = screen.getByTestId('display-actions');
  expect(dropdown).toBeDefined();
  fireEvent.change(dropdown, { target: { value: actionName } });

  const actionChangeDialog = screen.getByTestId('action-change-dialog');
  expect(actionChangeDialog).toBeDefined();
  const actionChangeDialogProceed = screen.getByTestId('action-change-dialog-proceed');
  expect(actionChangeDialogProceed).toBeDefined();
  fireEvent.click(actionChangeDialogProceed);

  switch (actionName) {
    case 'SRARC_SetConfig':
      await waitFor(() => expect(screen.getByTestId('set-dso-rules-config-header')).toBeDefined());
      break;
    case 'CRARC_SetConfig':
      await waitFor(() =>
        expect(screen.getByTestId('set-amulet-rules-config-header')).toBeDefined()
      );
      break;
  }
}
