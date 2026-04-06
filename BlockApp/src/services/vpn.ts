import { NativeModules } from 'react-native';

const { VpnModule } = NativeModules;

export const VpnService = {
  start: (): Promise<void> => VpnModule?.startVpn() ?? Promise.resolve(),
  stop: (): Promise<void> => VpnModule?.stopVpn() ?? Promise.resolve(),
  isRunning: (): Promise<boolean> => VpnModule?.isVpnRunning() ?? Promise.resolve(false),
};
