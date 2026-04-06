import { NativeModules } from 'react-native';

const { DatabaseModule } = NativeModules;

export interface BlockedUrl {
  id: number;
  url: string;
  description?: string;
  is_active: boolean;
  group_id?: number;
}

export interface Group {
  id: number;
  name: string;
  color: string;
  is_active: boolean;
}

// ── URLs ──────────────────────────────────────────────────────

export const getUrls = (): Promise<BlockedUrl[]> =>
  DatabaseModule.getUrls();

export const createUrl = (data: { url: string; description?: string; group_id?: number }): Promise<BlockedUrl> =>
  DatabaseModule.createUrl(data);

export const updateUrl = (id: number, data: Partial<BlockedUrl>): Promise<BlockedUrl> =>
  DatabaseModule.updateUrl(id, data);

export const deleteUrl = (id: number): Promise<void> =>
  DatabaseModule.deleteUrl(id);

export const toggleUrl = (id: number): Promise<BlockedUrl> =>
  DatabaseModule.toggleUrl(id);

// ── Groups ────────────────────────────────────────────────────

export const getGroups = (): Promise<Group[]> =>
  DatabaseModule.getGroups();

export const createGroup = (data: { name: string; color: string }): Promise<Group> =>
  DatabaseModule.createGroup(data);

export const updateGroup = (id: number, data: Partial<Group>): Promise<Group> =>
  DatabaseModule.updateGroup(id, data);

export const deleteGroup = (id: number): Promise<void> =>
  DatabaseModule.deleteGroup(id);

export const toggleGroup = (id: number): Promise<Group> =>
  DatabaseModule.toggleGroup(id);

// ── PIN / Settings ────────────────────────────────────────────

export const hasPin = (): Promise<{ has_pin: boolean }> =>
  DatabaseModule.hasPin();

export const verifyPin = (pin: string): Promise<{ valid: boolean }> =>
  DatabaseModule.verifyPin(pin);

export const setPin = (new_pin: string, current_pin?: string): Promise<{ ok: boolean }> =>
  DatabaseModule.setPin(new_pin, current_pin ?? null);

export const removePin = (pin: string): Promise<{ ok: boolean }> =>
  DatabaseModule.removePin(pin);
