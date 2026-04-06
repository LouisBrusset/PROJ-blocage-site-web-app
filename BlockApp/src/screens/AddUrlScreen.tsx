import React, { useState } from 'react';
import {
  View, Text, TextInput, TouchableOpacity, StyleSheet,
  ScrollView, Alert, KeyboardAvoidingView, Platform,
} from 'react-native';
import { getGroups, createUrl, updateUrl, type Group } from '../services/api';
import { useFocusEffect } from '@react-navigation/native';

export default function AddUrlScreen({ route, navigation }: any) {
  const editing = route.params?.item ?? null;

  const [url, setUrl] = useState(editing?.url ?? '');
  const [description, setDescription] = useState(editing?.description ?? '');
  const [groupId, setGroupId] = useState<number | null>(editing?.group_id ?? null);
  const [groups, setGroups] = useState<Group[]>([]);
  const [saving, setSaving] = useState(false);

  useFocusEffect(
    React.useCallback(() => {
      getGroups().then(setGroups).catch(() => {});
    }, [])
  );

  const save = async () => {
    const trimmed = url.trim().replace(/^https?:\/\//i, '').replace(/\/.*$/, '');
    if (!trimmed) {
      Alert.alert('Erreur', 'Veuillez saisir un domaine valide (ex: youtube.com)');
      return;
    }
    setSaving(true);
    try {
      const payload = {
        url: trimmed,
        description: description.trim() || undefined,
        group_id: groupId ?? undefined,
      };
      if (editing) {
        await updateUrl(editing.id, payload);
      } else {
        await createUrl(payload);
      }
      navigation.goBack();
    } catch (e: any) {
      Alert.alert('Erreur', e.message);
    } finally {
      setSaving(false);
    }
  };

  return (
    <KeyboardAvoidingView
      style={{ flex: 1 }}
      behavior={Platform.OS === 'ios' ? 'padding' : undefined}
    >
      <ScrollView style={s.container} contentContainerStyle={s.content}>
        <Text style={s.label}>Domaine à bloquer</Text>
        <TextInput
          style={s.input}
          value={url}
          onChangeText={setUrl}
          placeholder="youtube.com"
          autoCapitalize="none"
          keyboardType="url"
        />
        <Text style={s.hint}>Tout sous-domaine de ce domaine sera aussi bloqué.</Text>

        <Text style={s.label}>Description (optionnel)</Text>
        <TextInput
          style={s.input}
          value={description}
          onChangeText={setDescription}
          placeholder="Distractions, réseaux sociaux…"
        />

        <Text style={s.label}>Groupe (optionnel)</Text>
        <View style={s.groupRow}>
          <TouchableOpacity
            style={[s.groupChip, groupId === null && s.groupChipSelected]}
            onPress={() => setGroupId(null)}
          >
            <Text style={s.groupChipText}>Aucun</Text>
          </TouchableOpacity>
          {groups.map(g => (
            <TouchableOpacity
              key={g.id}
              style={[s.groupChip, groupId === g.id && s.groupChipSelected, { borderColor: g.color }]}
              onPress={() => setGroupId(g.id)}
            >
              <Text style={[s.groupChipText, groupId === g.id && { color: g.color }]}>{g.name}</Text>
            </TouchableOpacity>
          ))}
        </View>

        <TouchableOpacity style={s.saveBtn} onPress={save} disabled={saving}>
          <Text style={s.saveBtnText}>{saving ? 'Enregistrement…' : editing ? 'Mettre à jour' : 'Ajouter'}</Text>
        </TouchableOpacity>
      </ScrollView>
    </KeyboardAvoidingView>
  );
}

const s = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#f4f4fb' },
  content: { padding: 20, paddingBottom: 60 },
  label: { fontSize: 13, fontWeight: '600', color: '#5C6BC0', marginTop: 20, marginBottom: 6, textTransform: 'uppercase', letterSpacing: 0.5 },
  input: {
    backgroundColor: '#fff', borderRadius: 10, padding: 14,
    fontSize: 15, color: '#1a1a2e',
    shadowColor: '#000', shadowOpacity: 0.05, shadowRadius: 4, elevation: 1,
  },
  hint: { fontSize: 12, color: '#aaa', marginTop: 4 },
  groupRow: { flexDirection: 'row', flexWrap: 'wrap', gap: 8 },
  groupChip: {
    paddingHorizontal: 14, paddingVertical: 8,
    borderRadius: 20, borderWidth: 2, borderColor: '#ccc',
    backgroundColor: '#fff',
  },
  groupChipSelected: { borderColor: '#5C6BC0' },
  groupChipText: { fontSize: 14, color: '#666' },
  saveBtn: {
    marginTop: 32, backgroundColor: '#5C6BC0', borderRadius: 14,
    padding: 18, alignItems: 'center',
  },
  saveBtnText: { color: '#fff', fontSize: 17, fontWeight: '700' },
});
