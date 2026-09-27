import { ModelState } from './motion-controller.js';
import { Options } from './types.js';
export interface LoadContext {
    canvas: HTMLCanvasElement;
    state: ModelState;
    resize: () => void;
    emit: (event: 'loaded') => void;
    replaceCanvas: () => HTMLCanvasElement;
}
export declare function loadModel(ctx: LoadContext, options: Options): Promise<void>;
