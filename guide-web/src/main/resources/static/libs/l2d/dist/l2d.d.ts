import { L2DEventMap, Options, ParamInfo } from './types.js';
import { Emitter } from './emitter.js';
declare class L2D extends Emitter<L2DEventMap> {
    private canvas;
    private _state;
    private _motionCtrl;
    private _exprCtrl;
    constructor(canvas: HTMLCanvasElement);
    private _on;
    private _replaceCanvas;
    /**
     * 加载并渲染一个 Live2D 模型，自动识别 Cubism 2 / 6 版本。
     * 加载完成后触发 `loaded` 事件。
     * @param options - 模型加载选项，参见 {@link Options}
     */
    load(options: Options): Promise<void>;
    /** @deprecated 请使用 {@link load} 代替 */
    create(options: Options): Promise<void>;
    resize(): void;
    /** 返回当前绑定的 canvas 元素 */
    getCanvas(): HTMLCanvasElement;
    /**
     * 播放指定动作组中的动作。
     * @param group - 动作组名称
     * @param index - 组内动作索引，省略时随机播放
     * @param priority - 播放优先级，数值越大越优先
     */
    playMotion(group: string, index?: number, priority?: number): void;
    /**
     * 获取所有动作，结构为 `{ 组名: [文件路径, ...] }`。
     */
    getMotions(): Record<string, string[]>;
    /**
     * 通过动作文件路径播放动作，内部自动匹配对应的动作组和索引。
     * @param file - 动作文件路径，如 `'motions/idle.motion3.json'`
     * @param priority - 播放优先级
     */
    playMotionByFile(file: string, priority?: number): void;
    /** 获取所有可用表情的 ID 列表 */
    getExpressions(): string[];
    /**
     * 切换模型表情。
     * @param id - 表情 ID，省略时随机切换
     */
    setExpression(id?: string): void;
    /**
     * 设置动作声音文件的播放音量，与加载选项中的 `volume` 作用一致。
     * @param volume - 音量，范围 `0`（静音）~ `1`（最大）
     */
    setVolume(volume: number): void;
    /**
     * 设置模型缩放比例，与加载选项中的 `scale` 作用一致。
     * @param scale - 缩放比例，`1` 为原始大小
     */
    setScale(scale: number): void;
    /**
     * 设置模型在画布中的位置偏移，与加载选项中的 `position` 作用一致。
     * @param x - X 轴偏移，范围为-2 ~ 2
     * @param y - Y 轴偏移，范围为-2 ~ 2
     */
    setPosition(x: number, y: number): void;
    /**
     * 批量设置模型参数值，参数 ID。
     *
     * - **Cubism 2 模型**：打开模型目录下任意一个 `.mtn` 动作文件（文本格式），其中 `$curve` 段的每行开头即为参数 ID，例如 `PARAM_MOUTH_OPEN_Y`。
     *
     * - **Cubism 6 模型**：打开模型目录下任意一个 `.motion3.json` 动作文件，找到 `"Curves"` 数组，每个对象的 `"Id"` 即为参数名。
     * @example l2d.setParams({ ParamEyeLOpen: 0, ParamA: 1 })
     */
    setParams(params: Record<string, number>): void;
    /**
     * 获取模型所有参数的当前状态，每帧调用可实现实时追踪。
     * Cubism 2 / Cubism 6 均支持完整的 `id` / `value` / `min` / `max` / `default`。
     */
    getParams(): ParamInfo[];
    /**
     * 获取所有 hit area 的当前边界（相对 canvas 的 0~1 比例）。
     * 每帧调用可实现实时追踪。
     * @example
     * // canvas overlay
     * ctx.strokeRect(b.x * canvas.width, b.y * canvas.height, b.w * canvas.width, b.h * canvas.height)
     * // div overlay
     * el.style.left = `${b.x * 100}%`
     */
    getHitAreaBounds(): Array<{
        name: string;
        x: number;
        y: number;
        w: number;
        h: number;
    }>;
    /**
     * 销毁当前模型并释放 WebGL 资源，清空画布。
     * 画布 DOM 节点本身不会被移除。
     */
    destroy(): void;
}
export default L2D;
