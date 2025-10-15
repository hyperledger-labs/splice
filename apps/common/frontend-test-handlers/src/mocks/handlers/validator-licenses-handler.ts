import { rest, RestHandler } from 'msw';

import { ValidatorLicense } from '@daml.js/splice-amulet/lib/Splice/ValidatorLicense';

export function validatorLicensesHandler(baseUrl: string): RestHandler {
  return rest.get(`${baseUrl}/v0/admin/validator/licenses`, (req, res, ctx) => {
    const n = parseInt(req.url.searchParams.get('limit')!);
    const after = req.url.searchParams.get('after');
    const from = after ? parseInt(after) + 1 : 0;
    const aTimestamp = '2024-09-26T16:15:36Z';
    const validatorLicenses = Array.from({ length: n }, (_, i) => {
      const id = (i + from).toString();
      const validatorLicense: ValidatorLicense = {
        dso: 'dso',
        validator: `validator::${id}`,
        sponsor: 'sponsor',
        faucetState: {
          firstReceivedFor: { number: '1' },
          lastReceivedFor: { number: '10' },
          numCouponsMissed: '1',
        },
        metadata: { version: '1', lastUpdatedAt: aTimestamp, contactPoint: 'nowhere' },
        lastActiveAt: aTimestamp,
        weight: null,
        kind: null,
      };
      return {
        contract_id: id,
        created_at: aTimestamp,
        created_event_blob: '',
        payload: validatorLicense,
        template_id: ValidatorLicense.templateId,
      };
    });
    return res(
      ctx.json({
        validator_licenses: validatorLicenses,
        next_page_token: from + n,
      })
    );
  });
}
