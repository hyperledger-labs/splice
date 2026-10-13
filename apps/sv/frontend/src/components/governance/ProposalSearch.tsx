// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import React, { memo, useEffect, useRef, useState } from 'react';
import SearchIcon from '@mui/icons-material/Search';
import { Box, InputAdornment, Link, TextField, Typography } from '@mui/material';
import type { Theme } from '@mui/material/styles';
import { useNavigate, useSearchParams } from 'react-router';
import { ContractId } from '@daml/types';
import { VoteRequest } from '@daml.js/splice-dso-governance/lib/Splice/DsoRules';
import { CONTRACT_ID_VALIDATION_MESSAGE, isValidContractId } from '../../utils/proposalSearch';
import {
  fieldDescriptionSx,
  fieldSectionSx,
  fieldSectionTitleSx,
  singleLineFieldSx,
} from '../../themes/fieldStyles';
import { scrollableTextFieldSx } from '../ui/identifierStyles';

const searchTextFieldSx = (theme: Theme) => ({
  ...(typeof singleLineFieldSx === 'function' ? singleLineFieldSx(theme) : singleLineFieldSx),
  ...scrollableTextFieldSx,
});

function getEffectiveSearchQuery(value: string): string {
  const trimmed = value.trim();
  return isValidContractId(trimmed) ? trimmed : '';
}

function syncSearchQuery(
  value: string,
  lastSyncedSearchRef: React.MutableRefObject<string>,
  onSearchChange: (query: string) => void
): void {
  const effective = getEffectiveSearchQuery(value);
  if (effective === lastSyncedSearchRef.current) {
    return;
  }
  lastSyncedSearchRef.current = effective;
  onSearchChange(effective);
}

export interface ProposalSearchProps {
  onSearchChange: (query: string) => void;
}

export const ProposalSearch: React.FC<ProposalSearchProps> = memo(function ProposalSearch({
  onSearchChange,
}) {
  const navigate = useNavigate();
  const [searchParams, setSearchParams] = useSearchParams();
  const urlQuery = searchParams.get('q') ?? '';
  const [inputValue, setInputValue] = useState(urlQuery);
  /** Blocks stale URL from overwriting local input while a pending write is in flight. */
  const pendingUrlValueRef = useRef<string | null>(null);
  const lastSyncedSearchRef = useRef('');
  const onSearchChangeRef = useRef(onSearchChange);
  onSearchChangeRef.current = onSearchChange;

  useEffect(() => {
    setInputValue(prev => {
      if (prev === urlQuery) {
        if (pendingUrlValueRef.current === urlQuery) {
          pendingUrlValueRef.current = null;
        }
        return prev;
      }

      if (pendingUrlValueRef.current !== null) {
        if (urlQuery === pendingUrlValueRef.current) {
          pendingUrlValueRef.current = null;
        }
        return prev;
      }

      syncSearchQuery(urlQuery, lastSyncedSearchRef, query => {
        onSearchChangeRef.current(query);
      });
      return urlQuery;
    });
  }, [urlQuery]);

  const syncUrl = (value: string) => {
    const trimmed = value.trim();
    if (!trimmed || !isValidContractId(trimmed)) {
      setSearchParams(
        prev => {
          if (!prev.has('q')) {
            return prev;
          }
          const next = new URLSearchParams(prev);
          next.delete('q');
          return next;
        },
        { replace: true }
      );
      return;
    }

    pendingUrlValueRef.current = trimmed;
    setSearchParams(
      prev => {
        if (prev.get('q') === trimmed) {
          return prev;
        }
        const next = new URLSearchParams(prev);
        next.set('q', trimmed);
        return next;
      },
      { replace: true }
    );
  };

  const handleChange = (value: string) => {
    pendingUrlValueRef.current = value;
    setInputValue(value);
    syncSearchQuery(value, lastSyncedSearchRef, query => {
      onSearchChangeRef.current(query);
    });
    syncUrl(value);
  };

  const handleClear = () => {
    pendingUrlValueRef.current = '';
    lastSyncedSearchRef.current = '';
    setInputValue('');
    onSearchChangeRef.current('');
    syncUrl('');
  };

  const handleKeyDown = (event: React.KeyboardEvent<HTMLInputElement>) => {
    if (event.key !== 'Enter') {
      return;
    }

    const trimmed = inputValue.trim();
    if (isValidContractId(trimmed)) {
      event.preventDefault();
      syncSearchQuery(trimmed, lastSyncedSearchRef, query => {
        onSearchChangeRef.current(query);
      });
      syncUrl(trimmed);
      navigate(`/governance/proposals/${trimmed as ContractId<VoteRequest>}`);
    }
  };

  const showValidationError = inputValue.trim().length > 0 && !isValidContractId(inputValue);

  return (
    <Box
      sx={{
        ...fieldSectionSx,
        width: '100%',
        maxWidth: 960,
        mx: 'auto',
        mb: 3,
      }}
      data-testid="proposal-search"
    >
      <Typography sx={fieldSectionTitleSx} data-testid="proposal-search-title">
        Search Proposals
      </Typography>

      <TextField
        fullWidth
        variant="outlined"
        autoComplete="off"
        placeholder="Contract ID"
        value={inputValue}
        onChange={event => handleChange(event.target.value)}
        error={showValidationError}
        helperText={showValidationError ? CONTRACT_ID_VALIDATION_MESSAGE : undefined}
        sx={searchTextFieldSx}
        slotProps={{
          input: {
            startAdornment: (
              <InputAdornment position="start">
                <SearchIcon
                  sx={{ fontSize: 16, color: 'common.white' }}
                  data-testid="proposal-search-submit"
                />
              </InputAdornment>
            ),
          },
          htmlInput: {
            'data-testid': 'proposal-search-input',
            onKeyDown: handleKeyDown,
          },
        }}
      />

      {isValidContractId(inputValue) && (
        <Link
          component="button"
          type="button"
          onClick={handleClear}
          data-testid="proposal-search-clear"
          sx={{
            ...fieldDescriptionSx,
            alignSelf: 'flex-start',
            textDecoration: 'underline',
            cursor: 'pointer',
            border: 'none',
            background: 'none',
            p: 0,
          }}
        >
          Clear search
        </Link>
      )}
    </Box>
  );
});

export default ProposalSearch;
