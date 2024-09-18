// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import React from 'react';

import DragIndicatorIcon from '@mui/icons-material/DragIndicator';
import { Card, CardContent, IconButton, Paper, Stack, Typography } from '@mui/material';

export interface User {
  id: number;
  name: string;
}
interface RankingFormProps {
  users: User[];
  updateRanking: (ranking: User[]) => void;
}

const RankingForm: React.FC<RankingFormProps> = ({ users, updateRanking }) => {
  const handleDragStart = (e: React.DragEvent<HTMLDivElement>, index: number) => {
    e.dataTransfer.setData('text/plain', index.toString());
  };

  const handleDragOver = (e: React.DragEvent<HTMLDivElement>) => {
    e.preventDefault();
  };

  const handleDrop = (e: React.DragEvent<HTMLDivElement>, targetIndex: number) => {
    const sourceIndex = Number(e.dataTransfer.getData('text/plain'));
    const updatedRankings = [...users];

    const [draggedUser] = updatedRankings.splice(sourceIndex, 1);
    updatedRankings.splice(targetIndex, 0, draggedUser);

    updateRanking(updatedRankings);
  };

  return (
    <Card>
      <CardContent>
        {users.map((user, index) => (
          <Stack spacing={2} key={index}>
            # {index + 1} :
            <div
              key={user.id}
              draggable
              onDragStart={e => handleDragStart(e, index)}
              onDragOver={handleDragOver}
              onDrop={e => handleDrop(e, index)}
              style={{ marginBottom: '8px' }}
            >
              <Paper
                elevation={3}
                style={{ display: 'flex', alignItems: 'center', padding: '8px' }}
              >
                <IconButton id={`drag-${index}`} style={{ cursor: 'grab', marginRight: '8px' }}>
                  <DragIndicatorIcon />
                </IconButton>
                <Typography>{user.name}</Typography>
              </Paper>
            </div>
          </Stack>
        ))}
      </CardContent>
    </Card>
  );
};

export default RankingForm;
