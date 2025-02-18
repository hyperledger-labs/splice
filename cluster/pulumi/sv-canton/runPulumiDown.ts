import { PulumiAbortController } from '../pulumi';
import { downAllTheCantonStacks } from './pulumiDown';

const abortController = new PulumiAbortController();

downAllTheCantonStacks(abortController).catch(e => {
  console.error('Failed to run destroy');
  console.error(e);
  process.exit(1);
});
