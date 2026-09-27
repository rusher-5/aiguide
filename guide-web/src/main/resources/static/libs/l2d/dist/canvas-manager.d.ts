/**
 * 当 canvas 没有显式 CSS 尺寸时，用 HTML width/height 属性值补上 style，
 * 防止 resize 逻辑中 `clientWidth * dpr` 与属性值互相影响导致无限放大。
 */
export declare function ensureCssSize(canvas: HTMLCanvasElement): void;
export declare function cloneCanvas(old: HTMLCanvasElement): HTMLCanvasElement;
