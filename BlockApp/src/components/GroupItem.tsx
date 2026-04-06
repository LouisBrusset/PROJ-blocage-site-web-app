import React from 'react';
import {
  View, Text, Switch, TouchableOpacity, StyleSheet, Alert,
} from 'react-native';
import type { Group } from '../services/api';

interface Props {
  group: Group;
  urlCount: number;
  onToggle: (id: number, currentState: boolean) => void;
  onEdit: (group: Group) => void;
  onDelete: (id: number) => void;
}

export default function GroupItem({ group, urlCount, onToggle, onEdit, onDelete }: Props) {
  const confirmDelete = () => {
    Alert.alert('Supprimer le groupe', `Supprimer "${group.name}" ? Les URLs seront conservées.`, [
      { text: 'Annuler', style: 'cancel' },
      { text: 'Supprimer', style: 'destructive', onPress: () => onDelete(group.id) },
    ]);
  };

  return (
    <View style={[s.container, { borderLeftColor: group.color }]}>
      <View style={[s.dot, { backgroundColor: group.color }]} />
      <View style={s.middle}>
        <Text style={s.name}>{group.name}</Text>
        <Text style={s.count}>{urlCount} site{urlCount !== 1 ? 's' : ''}</Text>
      </View>
      <Switch
        value={group.is_active}
        onValueChange={() => onToggle(group.id, group.is_active)}
        trackColor={{ true: group.color, false: '#ccc' }}
        thumbColor="#fff"
      />
      <TouchableOpacity onPress={() => onEdit(group)} style={s.btn}>
        <Text style={s.icon}>✏️</Text>
      </TouchableOpacity>
      <TouchableOpacity onPress={confirmDelete} style={s.btn}>
        <Text style={s.icon}>🗑️</Text>
      </TouchableOpacity>
    </View>
  );
}

const s = StyleSheet.create({
  container: {
    flexDirection: 'row',
    alignItems: 'center',
    backgroundColor: '#fff',
    borderRadius: 12,
    padding: 14,
    marginBottom: 10,
    borderLeftWidth: 4,
    shadowColor: '#000',
    shadowOpacity: 0.06,
    shadowRadius: 6,
    elevation: 2,
    gap: 10,
  },
  dot: { width: 12, height: 12, borderRadius: 6 },
  middle: { flex: 1 },
  name: { fontSize: 15, fontWeight: '600', color: '#1a1a2e' },
  count: { fontSize: 12, color: '#888', marginTop: 2 },
  btn: { padding: 4 },
  icon: { fontSize: 16 },
});
