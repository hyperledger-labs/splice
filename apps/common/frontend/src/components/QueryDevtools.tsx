// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { ReactQueryDevtools } from '@tanstack/react-query-devtools';
import React from 'react';

// Wraps ReactQueryDevtools so environments that must keep browser consoles
// clean (e.g. CI integration tests, which fail on unexpected console errors)
// can opt out via VITE_DISABLE_QUERY_DEVTOOLS=true.
const QueryDevtools: React.FC = () => {
  if (import.meta.env.VITE_DISABLE_QUERY_DEVTOOLS === 'true') {
    return null;
  }
  return <ReactQueryDevtools initialIsOpen={false} />;
};

export default QueryDevtools;
