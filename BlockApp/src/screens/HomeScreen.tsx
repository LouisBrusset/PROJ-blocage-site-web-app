import React, { useCallback, useEffect, useState } from 'react';
import {
  View, Text, ScrollView, TouchableOpacity, StyleSheet,
  ActivityIndicator, Alert, RefreshControl, Switch,
} from 'react-native';
import { useFocusEffect } from '@react-navigation/native';

import {
  getUrls, getGroups, toggleUrl, toggleGroup, deleteUrl, verifyPin, hasPin,
  type BlockedUrl, type Group,
} from '../services/api';
import { VpnService } from '../services/vpn';
import UrlItem from '../components/UrlItem';
import PinModal from '../components/PinModal';

type PendingAction =
  | { type: 'url'; id: number }
  | { type: 'group'; id: number };

export default function HomeScreen({ navigation }: any) {
  const [urls, setUrls] = useState<BlockedUrl[]>([]);
  const [groups, setGroups] = useState<Group[]>([]);
  const [loading, setLoading] = useState(true);
  const [vpnRunning, setVpnRunning] = useState(false);
  const [pinVisible, setPinVisible] = useState(false);
  const [pendingAction, setPendingAction] = useState<PendingAction | null>(null);
  const [pinRequired, setPinRequired] = useState(false);

  const load = useCallback(async () => {
    try {
      const [u, g] = await Promise.all([getUrls(), getGroups()]);
      setUrls(u);
      setGroups(g);
    } catch (e: any) {
      Alert.alert('Erreur', 'Impossible de contacter le serveur.\n' + e.message);
    } finally {
      setLoading(false);
    }
  }, []);

  useFocusEffect(useCallback(() => {
    load();
    hasPin().then(r => setPinRequired(r.has_pin)).catch(() => {});
  }, [load]));

  useEffect(() => {
    VpnService.isRunning().then(setVpnRunning).catch(() => {});
  }, []);

  // ── URL toggle ────────────────────────────────────────────────

  const handleUrlToggle = async (id: number, currentState: boolean) => {
    if (currentState && pinRequired) {
      setPendingAction({ type: 'url', id });
      setPinVisible(true);
      return;
    }
    await doUrlToggle(id);
  };

  const doUrlToggle = async (id: number) => {
    try {
      const updated = await toggleUrl(id);
      const newUrls = urls.map(u => u.id === id ? updated : u);
      setUrls(newUrls);

      // Synchronisation automatique du groupe si tous ses sites passent au même état
      if (updated.group_id != null) {
        const groupUrls = newUrls.filter(u => u.group_id === updated.group_id);
        if (groupUrls.length > 0) {
          const allOff = groupUrls.every(u => !u.is_active);
          const allOn  = groupUrls.every(u => u.is_active);
          const group  = groups.find(g => g.id === updated.group_id);
          if (allOff && group?.is_active)   await doGroupToggle(updated.group_id);
          if (allOn  && !group?.is_active)  await doGroupToggle(updated.group_id);
        }
      }
    } catch (e: any) {
      Alert.alert('Erreur', e.message);
    }
  };

  // ── Group toggle ──────────────────────────────────────────────

  const handleGroupToggle = async (id: number, currentState: boolean) => {
    if (currentState && pinRequired) {
      setPendingAction({ type: 'group', id });
      setPinVisible(true);
      return;
    }
    await doGroupToggle(id);
  };

  const doGroupToggle = async (id: number) => {
    try {
      const updatedGroup = await toggleGroup(id);
      setGroups(prev => prev.map(g => g.id === id ? updatedGroup : g));
      // Recharger toutes les URLs pour refléter la cascade côté serveur
      const freshUrls = await getUrls();
      setUrls(freshUrls);
    } catch (e: any) {
      Alert.alert('Erreur', e.message);
    }
  };

  // ── PIN success ───────────────────────────────────────────────

  const handlePinSuccess = async () => {
    setPinVisible(false);
    if (pendingAction?.type === 'url') {
      await doUrlToggle(pendingAction.id);
    } else if (pendingAction?.type === 'group') {
      await doGroupToggle(pendingAction.id);
    }
    setPendingAction(null);
  };

  // ── Delete ────────────────────────────────────────────────────

  const handleDelete = async (id: number) => {
    try {
      await deleteUrl(id);
      setUrls(prev => prev.filter(u => u.id !== id));
    } catch (e: any) {
      Alert.alert('Erreur', e.message);
    }
  };

  // ── VPN ───────────────────────────────────────────────────────

  const toggleVpn = async () => {
    try {
      if (vpnRunning) {
        await VpnService.stop();
        setVpnRunning(false);
      } else {
        await VpnService.start();
        setVpnRunning(true);
      }
    } catch (e: any) {
      Alert.alert('VPN', e.message);
    }
  };

  // ── Sections ──────────────────────────────────────────────────

  const groupedSections = groups
    .map(g => ({ group: g, urls: urls.filter(u => u.group_id === g.id) }))
    .filter(s => s.urls.length > 0);

  const ungroupedUrls = urls.filter(u => !u.group_id);

  if (loading) {
    return <View style={s.center}><ActivityIndicator size="large" color="#5C6BC0" /></View>;
  }

  return (
    <View style={s.container}>
      {/* VPN toggle banner */}
      <TouchableOpacity
        style={[s.vpnBanner, vpnRunning ? s.vpnOn : s.vpnOff]}
        onPress={toggleVpn}
      >
        <Text style={s.vpnIcon}>{vpnRunning ? '🛡️' : '⭕'}</Text>
        <View>
          <Text style={s.vpnTitle}>Blocage {vpnRunning ? 'ACTIF' : 'INACTIF'}</Text>
          <Text style={s.vpnSub}>Appuyer pour {vpnRunning ? 'désactiver' : 'activer'}</Text>
        </View>
      </TouchableOpacity>

      <ScrollView
        contentContainerStyle={s.list}
        refreshControl={<RefreshControl refreshing={loading} onRefresh={load} />}
      >
        {groupedSections.length === 0 && ungroupedUrls.length === 0 && (
          <Text style={s.empty}>Aucun site bloqué.{'\n'}Appuyez sur + pour en ajouter un.</Text>
        )}

        {/* Sections par groupe */}
        {groupedSections.map(({ group, urls: groupUrls }) => (
          <View key={group.id} style={[s.section, { borderLeftColor: group.color }]}>
            {/* En-tête de groupe */}
            <View style={s.sectionHeader}>
              <View style={[s.colorDot, { backgroundColor: group.color }]} />
              <Text style={[s.sectionTitle, { color: group.color }]}>{group.name}</Text>
              <Text style={s.urlCount}>{groupUrls.length} site{groupUrls.length > 1 ? 's' : ''}</Text>
              <Switch
                value={group.is_active}
                onValueChange={() => handleGroupToggle(group.id, group.is_active)}
                trackColor={{ true: group.color, false: '#ccc' }}
                thumbColor="#fff"
              />
            </View>

            {/* URLs du groupe */}
            {groupUrls.map(item => (
              <UrlItem
                key={item.id}
                item={item}
                onToggle={handleUrlToggle}
                onEdit={item => navigation.navigate('AddUrl', { item })}
                onDelete={handleDelete}
              />
            ))}
          </View>
        ))}

        {/* URLs sans groupe */}
        {ungroupedUrls.length > 0 && (
          <View style={[s.section, s.sectionNoGroup]}>
            <View style={s.sectionHeader}>
              <Text style={s.sectionTitleGrey}>Sans groupe</Text>
              <Text style={s.urlCount}>{ungroupedUrls.length} site{ungroupedUrls.length > 1 ? 's' : ''}</Text>
            </View>
            {ungroupedUrls.map(item => (
              <UrlItem
                key={item.id}
                item={item}
                onToggle={handleUrlToggle}
                onEdit={item => navigation.navigate('AddUrl', { item })}
                onDelete={handleDelete}
              />
            ))}
          </View>
        )}
      </ScrollView>

      <TouchableOpacity
        style={s.fab}
        onPress={() => navigation.navigate('AddUrl', { item: null })}
      >
        <Text style={s.fabText}>+</Text>
      </TouchableOpacity>

      <PinModal
        visible={pinVisible}
        onSuccess={handlePinSuccess}
        onCancel={() => { setPinVisible(false); setPendingAction(null); }}
        onVerify={async (pin) => {
          const r = await verifyPin(pin);
          return r.valid;
        }}
      />
    </View>
  );
}

const s = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#f4f4fb' },
  center: { flex: 1, justifyContent: 'center', alignItems: 'center' },
  vpnBanner: {
    flexDirection: 'row', alignItems: 'center', gap: 12,
    margin: 16, borderRadius: 14, padding: 16,
  },
  vpnOn: { backgroundColor: '#5C6BC0' },
  vpnOff: { backgroundColor: '#9e9e9e' },
  vpnIcon: { fontSize: 28 },
  vpnTitle: { color: '#fff', fontWeight: '700', fontSize: 16 },
  vpnSub: { color: 'rgba(255,255,255,0.8)', fontSize: 12 },
  list: { paddingHorizontal: 16, paddingBottom: 100 },
  empty: { textAlign: 'center', color: '#aaa', marginTop: 60, fontSize: 15, lineHeight: 24 },
  section: {
    backgroundColor: '#fff',
    borderRadius: 14,
    marginBottom: 16,
    borderLeftWidth: 4,
    shadowColor: '#000',
    shadowOpacity: 0.06,
    shadowRadius: 6,
    elevation: 2,
    overflow: 'hidden',
  },
  sectionNoGroup: {
    borderLeftColor: '#ccc',
  },
  sectionHeader: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: 14,
    paddingVertical: 12,
    gap: 8,
    borderBottomWidth: 1,
    borderBottomColor: '#f0f0f7',
  },
  colorDot: {
    width: 10,
    height: 10,
    borderRadius: 5,
  },
  sectionTitle: {
    flex: 1,
    fontSize: 15,
    fontWeight: '700',
  },
  sectionTitleGrey: {
    flex: 1,
    fontSize: 15,
    fontWeight: '700',
    color: '#888',
  },
  urlCount: {
    fontSize: 12,
    color: '#aaa',
  },
  fab: {
    position: 'absolute', bottom: 28, right: 24,
    width: 56, height: 56, borderRadius: 28,
    backgroundColor: '#5C6BC0',
    justifyContent: 'center', alignItems: 'center',
    shadowColor: '#5C6BC0', shadowOpacity: 0.5, shadowRadius: 8, elevation: 6,
  },
  fabText: { color: '#fff', fontSize: 30, lineHeight: 34 },
});
