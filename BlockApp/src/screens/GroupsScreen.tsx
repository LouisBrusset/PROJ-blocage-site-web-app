import React, { useState, useCallback } from 'react';
import {
  View, Text, FlatList, TouchableOpacity, StyleSheet,
  Modal, TextInput, Alert, RefreshControl,
} from 'react-native';
import { useFocusEffect } from '@react-navigation/native';
import {
  getGroups, getUrls, createGroup, updateGroup, deleteGroup, toggleGroup,
  verifyPin, hasPin, type Group,
} from '../services/api';
import GroupItem from '../components/GroupItem';
import PinModal from '../components/PinModal';

const COLORS = ['#5C6BC0', '#E53935', '#43A047', '#FB8C00', '#8E24AA', '#00ACC1', '#F06292', '#26A69A'];

export default function GroupsScreen() {
  const [groups, setGroups] = useState<Group[]>([]);
  const [urlCounts, setUrlCounts] = useState<Record<number, number>>({});
  const [loading, setLoading] = useState(true);
  const [modalVisible, setModalVisible] = useState(false);
  const [editing, setEditing] = useState<Group | null>(null);
  const [name, setName] = useState('');
  const [color, setColor] = useState(COLORS[0]);
  const [pinVisible, setPinVisible] = useState(false);
  const [pendingToggleId, setPendingToggleId] = useState<number | null>(null);
  const [pinRequired, setPinRequired] = useState(false);

  const load = useCallback(async () => {
    try {
      const [g, u] = await Promise.all([getGroups(), getUrls()]);
      setGroups(g);
      const counts: Record<number, number> = {};
      u.forEach(url => { if (url.group_id) counts[url.group_id] = (counts[url.group_id] ?? 0) + 1; });
      setUrlCounts(counts);
    } catch (e: any) {
      Alert.alert('Erreur', e.message);
    } finally {
      setLoading(false);
    }
  }, []);

  useFocusEffect(useCallback(() => {
    load();
    hasPin().then(r => setPinRequired(r.has_pin)).catch(() => {});
  }, [load]));

  const openCreate = () => {
    setEditing(null);
    setName('');
    setColor(COLORS[0]);
    setModalVisible(true);
  };

  const openEdit = (g: Group) => {
    setEditing(g);
    setName(g.name);
    setColor(g.color);
    setModalVisible(true);
  };

  const save = async () => {
    if (!name.trim()) { Alert.alert('Erreur', 'Nom requis'); return; }
    try {
      if (editing) {
        const updated = await updateGroup(editing.id, { name: name.trim(), color });
        setGroups(prev => prev.map(g => g.id === editing.id ? updated : g));
      } else {
        const created = await createGroup({ name: name.trim(), color });
        setGroups(prev => [...prev, created]);
      }
      setModalVisible(false);
    } catch (e: any) {
      Alert.alert('Erreur', e.message);
    }
  };

  const handleToggle = async (id: number, currentState: boolean) => {
    if (currentState && pinRequired) {
      setPendingToggleId(id);
      setPinVisible(true);
      return;
    }
    await doToggle(id);
  };

  const doToggle = async (id: number) => {
    try {
      const updated = await toggleGroup(id);
      setGroups(prev => prev.map(g => g.id === id ? updated : g));
    } catch (e: any) {
      Alert.alert('Erreur', e.message);
    }
  };

  const handleDelete = async (id: number) => {
    try {
      await deleteGroup(id);
      setGroups(prev => prev.filter(g => g.id !== id));
    } catch (e: any) {
      Alert.alert('Erreur', e.message);
    }
  };

  return (
    <View style={s.container}>
      <FlatList
        data={groups}
        keyExtractor={item => String(item.id)}
        renderItem={({ item }) => (
          <GroupItem
            group={item}
            urlCount={urlCounts[item.id] ?? 0}
            onToggle={handleToggle}
            onEdit={openEdit}
            onDelete={handleDelete}
          />
        )}
        contentContainerStyle={s.list}
        refreshControl={<RefreshControl refreshing={loading} onRefresh={load} />}
        ListEmptyComponent={
          <Text style={s.empty}>Aucun groupe.{'\n'}Appuyez sur + pour en créer un.</Text>
        }
      />

      <TouchableOpacity style={s.fab} onPress={openCreate}>
        <Text style={s.fabText}>+</Text>
      </TouchableOpacity>

      {/* Create/Edit modal */}
      <Modal visible={modalVisible} transparent animationType="slide">
        <View style={s.overlay}>
          <View style={s.card}>
            <Text style={s.cardTitle}>{editing ? 'Modifier le groupe' : 'Nouveau groupe'}</Text>

            <Text style={s.inputLabel}>Nom</Text>
            <TextInput
              style={s.input}
              value={name}
              onChangeText={setName}
              placeholder="Ex: Réseaux sociaux"
              autoFocus
            />

            <Text style={s.inputLabel}>Couleur</Text>
            <View style={s.colorRow}>
              {COLORS.map(c => (
                <TouchableOpacity
                  key={c}
                  style={[s.colorDot, { backgroundColor: c }, color === c && s.colorSelected]}
                  onPress={() => setColor(c)}
                />
              ))}
            </View>

            <View style={s.modalBtns}>
              <TouchableOpacity style={s.cancelBtn} onPress={() => setModalVisible(false)}>
                <Text style={s.cancelText}>Annuler</Text>
              </TouchableOpacity>
              <TouchableOpacity style={s.saveBtn} onPress={save}>
                <Text style={s.saveText}>Enregistrer</Text>
              </TouchableOpacity>
            </View>
          </View>
        </View>
      </Modal>

      <PinModal
        visible={pinVisible}
        onSuccess={async () => {
          setPinVisible(false);
          if (pendingToggleId !== null) { await doToggle(pendingToggleId); setPendingToggleId(null); }
        }}
        onCancel={() => { setPinVisible(false); setPendingToggleId(null); }}
        onVerify={async (pin) => { const r = await verifyPin(pin); return r.valid; }}
      />
    </View>
  );
}

const s = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#f4f4fb' },
  list: { padding: 16, paddingBottom: 100 },
  empty: { textAlign: 'center', color: '#aaa', marginTop: 60, fontSize: 15, lineHeight: 24 },
  fab: {
    position: 'absolute', bottom: 28, right: 24,
    width: 56, height: 56, borderRadius: 28,
    backgroundColor: '#5C6BC0',
    justifyContent: 'center', alignItems: 'center',
    shadowColor: '#5C6BC0', shadowOpacity: 0.5, shadowRadius: 8, elevation: 6,
  },
  fabText: { color: '#fff', fontSize: 30, lineHeight: 34 },
  overlay: { flex: 1, backgroundColor: 'rgba(0,0,0,0.4)', justifyContent: 'flex-end' },
  card: { backgroundColor: '#fff', borderTopLeftRadius: 24, borderTopRightRadius: 24, padding: 28 },
  cardTitle: { fontSize: 18, fontWeight: '700', color: '#1a1a2e', marginBottom: 20 },
  inputLabel: { fontSize: 13, color: '#5C6BC0', fontWeight: '600', marginBottom: 6 },
  input: {
    backgroundColor: '#f4f4fb', borderRadius: 10, padding: 14,
    fontSize: 15, marginBottom: 16,
  },
  colorRow: { flexDirection: 'row', gap: 10, marginBottom: 24 },
  colorDot: { width: 30, height: 30, borderRadius: 15 },
  colorSelected: { borderWidth: 3, borderColor: '#fff', shadowColor: '#000', shadowOpacity: 0.3, shadowRadius: 4, elevation: 4 },
  modalBtns: { flexDirection: 'row', gap: 12 },
  cancelBtn: { flex: 1, padding: 16, borderRadius: 12, alignItems: 'center', backgroundColor: '#f0f0f7' },
  cancelText: { color: '#666', fontWeight: '600' },
  saveBtn: { flex: 1, padding: 16, borderRadius: 12, alignItems: 'center', backgroundColor: '#5C6BC0' },
  saveText: { color: '#fff', fontWeight: '700' },
});
