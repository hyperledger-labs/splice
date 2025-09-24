// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import { screen, fireEvent } from '@testing-library/react';
import { expect } from 'vitest';
import { MemoryRouter, useNavigate } from 'react-router-dom';
import { ThemeProvider } from '@mui/material';
import { SvConfigProvider, useSvConfig } from '../utils';
import {
  AuthProvider,
  SvClientProvider,
  theme,
  UserProvider,
} from '@lfdecentralizedtrust/splice-common-frontend';
import { SvAdminClientProvider } from '../contexts/SvAdminServiceContext';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { replaceEqualDeep } from '@lfdecentralizedtrust/splice-common-frontend-utils';

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
              <SvAdminClientProvider url={config.services.sv.url}>{children}</SvAdminClientProvider>
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

export function changeAction(actionName: string = 'SRARC_SetConfig'): void {
  const dropdown = screen.getByTestId('display-actions');
  expect(dropdown).toBeDefined();
  fireEvent.change(dropdown!, { target: { value: actionName } });

  const actionChangeDialog = screen.getByTestId('action-change-dialog');
  expect(actionChangeDialog).toBeDefined();
  const actionChangeDialogProceed = screen.getByTestId('action-change-dialog-proceed');
  expect(actionChangeDialogProceed).toBeDefined();
  fireEvent.click(actionChangeDialogProceed);
}
