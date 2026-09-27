import { default as Cubism2Model } from './vendor/cubism2/index.js';
import { AppDelegate as Cubism6Model } from './vendor/cubism6/index.js';
export interface ModelState {
    currentVersion: number | null;
    l2d2Model: Cubism2Model | null;
    l2d6Model: Cubism6Model | null;
}
export declare class MotionController {
    private state;
    constructor(state: ModelState);
    private isReady;
    playMotion(group: string, index?: number, priority?: number): void;
    getMotionGroups(): Record<string, number>;
    getMotions(): Record<string, string[]>;
    playMotionByFile(file: string, priority?: number): void;
}
