import { API_BASE_URL } from '../config';

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

async function request<T>(path: string, options?: RequestInit): Promise<T> {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 5000);
  try {
    const res = await fetch(`${API_BASE_URL}${path}`, {
      headers: { 'Content-Type': 'application/json' },
      signal: controller.signal,
      ...options,
    });
    if (!res.ok) {
      const err = await res.text();
      throw new Error(err || `HTTP ${res.status}`);
    }
    if (res.status === 204) return undefined as T;
    return res.json();
  } catch (e: any) {
    if (e.name === 'AbortError') throw new Error(`Impossible de joindre le serveur (${API_BASE_URL})`);
    throw e;
  } finally {
    clearTimeout(timeout);
  }
}

// ── URLs ──────────────────────────────────────────────────────

export const getUrls = () => request<BlockedUrl[]>('/urls');

export const createUrl = (data: { url: string; description?: string; group_id?: number }) =>
  request<BlockedUrl>('/urls', { method: 'POST', body: JSON.stringify(data) });

export const updateUrl = (id: number, data: Partial<BlockedUrl>) =>
  request<BlockedUrl>(`/urls/${id}`, { method: 'PUT', body: JSON.stringify(data) });

export const deleteUrl = (id: number) =>
  request<void>(`/urls/${id}`, { method: 'DELETE' });

export const toggleUrl = (id: number) =>
  request<BlockedUrl>(`/urls/${id}/toggle`, { method: 'PATCH' });

// ── Groups ────────────────────────────────────────────────────

export const getGroups = () => request<Group[]>('/groups');

export const createGroup = (data: { name: string; color: string }) =>
  request<Group>('/groups', { method: 'POST', body: JSON.stringify(data) });

export const updateGroup = (id: number, data: Partial<Group>) =>
  request<Group>(`/groups/${id}`, { method: 'PUT', body: JSON.stringify(data) });

export const deleteGroup = (id: number) =>
  request<void>(`/groups/${id}`, { method: 'DELETE' });

export const toggleGroup = (id: number) =>
  request<Group>(`/groups/${id}/toggle`, { method: 'PATCH' });

// ── PIN / Settings ────────────────────────────────────────────

export const hasPin = () => request<{ has_pin: boolean }>('/settings/has-pin');

export const verifyPin = (pin: string) =>
  request<{ valid: boolean }>('/settings/verify-pin', {
    method: 'POST',
    body: JSON.stringify({ pin }),
  });

export const setPin = (new_pin: string, current_pin?: string) =>
  request<{ ok: boolean }>('/settings/set-pin', {
    method: 'POST',
    body: JSON.stringify({ new_pin, current_pin }),
  });

export const removePin = (pin: string) =>
  request<{ ok: boolean }>('/settings/pin', {
    method: 'DELETE',
    body: JSON.stringify({ pin }),
  });
