import { setupServer, SetupServer } from 'msw/node';

import { Services } from '../setup/setup';
import { buildJsonApiMock } from './handlers/json-api';
import { buildScanMock } from './handlers/scan-api';
import { buildSplitwellMock } from './handlers/splitwell-api';

export const buildServer = ({ jsonApi, scan, splitwell }: Services): SetupServer =>
  setupServer(
    ...buildJsonApiMock(jsonApi.url),
    ...buildScanMock(scan.url),
    ...buildSplitwellMock(splitwell.url)
  );
