declare module 'shared/utils/logger.js' {
  export const logger: {
    info: (message: string, ...args: any[]) => void;
    error: (message: string, ...args: any[]) => void;
    warn: (message: string, ...args: any[]) => void;
    debug: (message: string, ...args: any[]) => void;
  };
}

declare module 'shared/*' {
  const value: any;
  export default value;
}