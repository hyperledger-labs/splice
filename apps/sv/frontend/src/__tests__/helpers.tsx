// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import {
  AuthProvider,
  SvClientProvider,
  theme,
  UserProvider,
} from '@canton-network/splice-common-frontend';
import { replaceEqualDeep } from '@canton-network/splice-common-frontend-utils';
import { ThemeProvider } from '@mui/material';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { screen } from '@testing-library/react';
import { MemoryRouter, useNavigate } from 'react-router';
import { Toaster } from 'sonner';
import { expect } from 'vitest';
import { SvAdminClientProvider } from '../contexts/SvAdminServiceContext';
import { SvAppVotesHooksProvider } from '../contexts/SvAppVotesHooksContext';
import { SvConfigProvider, useSvConfig } from '../utils';
import { UserEvent } from '@testing-library/user-event';

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

export const Wrapper: React.FC<{
  children: React.ReactNode;
  initialEntries?: string[];
}> = ({ children, initialEntries }) => {
  return (
    <MemoryRouter initialEntries={initialEntries}>
      <SvConfigProvider>
        <WrapperProviders children={children} />
        <Toaster richColors />
      </SvConfigProvider>
    </MemoryRouter>
  );
};

export async function navigateToGovernancePage(user: UserEvent): Promise<void> {
  expect(await screen.findByTestId('navlink-governance')).toBeInTheDocument();
  await user.click(screen.getByTestId('navlink-governance'));
}
