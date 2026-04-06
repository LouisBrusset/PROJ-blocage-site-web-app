import { NativeModules, PermissionsAndroid, Platform } from 'react-native';

const { WifiMonitorModule } = NativeModules;

/** Demande les permissions nécessaires (localisation + notifications).
 *  Retourne true si la localisation est accordée (minimum requis pour le SSID). */
export async function requestWifiPermissions(): Promise<boolean> {
  if (Platform.OS !== 'android') return false;
  try {
    const perms: string[] = [PermissionsAndroid.PERMISSIONS.ACCESS_FINE_LOCATION];
    const apiLevel = typeof Platform.Version === 'number'
      ? Platform.Version
      : parseInt(Platform.Version, 10);
    if (apiLevel >= 33) perms.push(PermissionsAndroid.PERMISSIONS.POST_NOTIFICATIONS as any);

    const results = await PermissionsAndroid.requestMultiple(perms as any);
    return results[PermissionsAndroid.PERMISSIONS.ACCESS_FINE_LOCATION]
      === PermissionsAndroid.RESULTS.GRANTED;
  } catch {
    return false;
  }
}

export const WifiMonitor = {
  start: (): Promise<void> =>
    WifiMonitorModule?.startMonitoring() ?? Promise.resolve(),

  stop: (): Promise<void> =>
    WifiMonitorModule?.stopMonitoring() ?? Promise.resolve(),

  getCurrentSsid: (): Promise<string | null> =>
    WifiMonitorModule?.getCurrentSsid() ?? Promise.resolve(null),

  getTrustedNetworks: (): Promise<string[]> =>
    WifiMonitorModule?.getTrustedNetworks() ?? Promise.resolve([]),

  addTrustedNetwork: (ssid: string): Promise<void> =>
    WifiMonitorModule?.addTrustedNetwork(ssid) ?? Promise.resolve(),

  removeTrustedNetwork: (ssid: string): Promise<void> =>
    WifiMonitorModule?.removeTrustedNetwork(ssid) ?? Promise.resolve(),

  /** Retourne true si la surveillance était activée lors de la dernière session */
  isMonitoringEnabled: (): Promise<boolean> =>
    WifiMonitorModule?.isMonitoringEnabled() ?? Promise.resolve(false),
};
