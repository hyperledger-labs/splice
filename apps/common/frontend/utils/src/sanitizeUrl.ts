import { sanitizeUrl as sanitize } from '@braintree/sanitize-url';

export const sanitizeUrl = (url: string) => {
  if (isStringEmptyOrUndefined(url)) return '';

  return sanitize(url);
};

function isStringEmptyOrUndefined(str: string | undefined | null): boolean {
  return str === undefined || str === null || str.trim() === '';
}
