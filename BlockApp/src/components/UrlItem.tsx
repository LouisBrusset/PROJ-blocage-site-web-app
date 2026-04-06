import React from 'react';
import {
  View, Text, Switch, TouchableOpacity, StyleSheet, Alert,
} from 'react-native';
import type { BlockedUrl } from '../services/api';

interface Props {
  item: BlockedUrl;
  onToggle: (id: number, currentState: boolean) => void;
  onEdit: (item: BlockedUrl) => void;
  onDelete: (id: number) => void;
}

export default function UrlItem({ item, onToggle, onEdit, onDelete }: Props) {
  const confirmDelete = () => {
    Alert.alert('Supprimer', `Supprimer "${item.url}" ?`, [
      { text: 'Annuler', style: 'cancel' },
      { text: 'Supprimer', style: 'destructive', onPress: () => onDelete(item.id) },
    ]);
  };

  return (
    <View style={s.container}>
      <View style={s.left}>
        <Text style={s.url} numberOfLines={1}>{item.url}</Text>
        {item.description ? (
          <Text style={s.desc} numberOfLines={1}>{item.description}</Text>
        ) : null}
      </View>
      <View style={s.right}>
        <Switch
          value={item.is_active}
          onValueChange={() => onToggle(item.id, item.is_active)}
          trackColor={{ true: '#5C6BC0', false: '#ccc' }}
          thumbColor="#fff"
        />
        <TouchableOpacity
          onPress={() => onEdit(item)}
          style={[s.editBtn, item.is_active && s.editBtnDisabled]}
          disabled={item.is_active}
        >
          <Text style={[s.editIcon, item.is_active && s.iconDisabled]}>✏️</Text>
        </TouchableOpacity>
        <TouchableOpacity
          onPress={confirmDelete}
          style={[s.editBtn, item.is_active && s.editBtnDisabled]}
          disabled={item.is_active}
        >
          <Text style={[s.editIcon, item.is_active && s.iconDisabled]}>🗑️</Text>
        </TouchableOpacity>
      </View>
    </View>
  );
}

const s = StyleSheet.create({
  container: {
    flexDirection: 'row',
    alignItems: 'center',
    backgroundColor: '#fff',
    borderRadius: 10,
    padding: 12,
    marginBottom: 8,
    shadowColor: '#000',
    shadowOpacity: 0.04,
    shadowRadius: 4,
    elevation: 1,
  },
  left: { flex: 1, marginRight: 8 },
  url: { fontSize: 15, fontWeight: '600', color: '#1a1a2e' },
  desc: { fontSize: 12, color: '#aaa', marginTop: 2 },
  right: { flexDirection: 'row', alignItems: 'center', gap: 4 },
  editBtn: { padding: 4 },
  editBtnDisabled: { opacity: 0.25 },
  editIcon: { fontSize: 16 },
  iconDisabled: { opacity: 0.4 },
});
