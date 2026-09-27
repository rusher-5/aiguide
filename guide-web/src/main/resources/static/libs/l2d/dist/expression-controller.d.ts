import { ModelState } from './motion-controller.js';
export declare class ExpressionController {
    private state;
    constructor(state: ModelState);
    private isReady;
    getExpressions(): string[];
    setExpression(id?: string): void;
}
