import { installController } from './controller';
import { installRunnerScaleSet } from './runners';

const controller = installController();
installRunnerScaleSet(controller);
