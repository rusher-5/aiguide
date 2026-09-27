export declare class Emitter<EventMap extends Record<keyof EventMap, (...args: any[]) => void>> {
    private _listeners;
    on<K extends keyof EventMap>(event: K, listener: EventMap[K]): this;
    protected emit<K extends keyof EventMap>(event: K, ...args: Parameters<EventMap[K]>): void;
}
