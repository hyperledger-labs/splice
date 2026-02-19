// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import React from 'react';
import { Navigate } from 'react-router';
import { useIsDevelopmentFundManager } from '../hooks/useIsDevelopmentFundManager';

const RequireDevelopmentFundManager: React.FC<React.PropsWithChildren> = ({ children }) => {
    const isManager = useIsDevelopmentFundManager();

    if (!isManager) {
        return <Navigate to="/" replace />;
    }

    return <>{children}</>;
};

export default RequireDevelopmentFundManager;
