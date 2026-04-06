import React, { useState } from 'react';
import {
  Modal, View, Text, TouchableOpacity, StyleSheet, Vibration,
} from 'react-native';

interface Props {
  visible: boolean;
  onSuccess: () => void;
  onCancel: () => void;
  onVerify: (pin: string) => Promise<boolean>;
  title?: string;
}

const KEYS = ['1', '2', '3', '4', '5', '6', '7', '8', '9', '', '0', '⌫'];

export default function PinModal({ visible, onSuccess, onCancel, onVerify, title }: Props) {
  const [pin, setPin] = useState('');
  const [error, setError] = useState(false);

  const handleKey = async (key: string) => {
    if (key === '⌫') {
      setPin(p => p.slice(0, -1));
      setError(false);
      return;
    }
    if (key === '') return;

    const next = pin + key;
    setPin(next);

    if (next.length === 4) {
      try {
        const valid = await onVerify(next);
        if (valid) {
          setPin('');
          setError(false);
          onSuccess();
        } else {
          Vibration.vibrate(300);
          setError(true);
          setTimeout(() => { setPin(''); setError(false); }, 600);
        }
      } catch {
        Vibration.vibrate(300);
        setError(true);
        setTimeout(() => { setPin(''); setError(false); }, 600);
      }
    }
  };

  return (
    <Modal visible={visible} transparent animationType="fade">
      <View style={s.overlay}>
        <View style={s.card}>
          <Text style={s.title}>{title ?? 'Entrez votre PIN'}</Text>
          <View style={s.dots}>
            {[0, 1, 2, 3].map(i => (
              <View
                key={i}
                style={[s.dot, pin.length > i && (error ? s.dotError : s.dotFilled)]}
              />
            ))}
          </View>
          {error && <Text style={s.errorText}>PIN incorrect</Text>}
          <View style={s.grid}>
            {KEYS.map((key, idx) => (
              <TouchableOpacity
                key={idx}
                style={[s.key, key === '' && s.keyHidden]}
                onPress={() => handleKey(key)}
                disabled={key === ''}
              >
                <Text style={s.keyText}>{key}</Text>
              </TouchableOpacity>
            ))}
          </View>
          <TouchableOpacity onPress={() => { setPin(''); setError(false); onCancel(); }}>
            <Text style={s.cancel}>Annuler</Text>
          </TouchableOpacity>
        </View>
      </View>
    </Modal>
  );
}

const s = StyleSheet.create({
  overlay: {
    flex: 1,
    backgroundColor: 'rgba(0,0,0,0.55)',
    justifyContent: 'center',
    alignItems: 'center',
  },
  card: {
    backgroundColor: '#fff',
    borderRadius: 20,
    padding: 28,
    alignItems: 'center',
    width: 300,
  },
  title: { fontSize: 18, fontWeight: '600', marginBottom: 20, color: '#1a1a2e' },
  dots: { flexDirection: 'row', gap: 16, marginBottom: 8 },
  dot: {
    width: 14, height: 14, borderRadius: 7,
    borderWidth: 2, borderColor: '#5C6BC0',
  },
  dotFilled: { backgroundColor: '#5C6BC0' },
  dotError: { backgroundColor: '#e53935', borderColor: '#e53935' },
  errorText: { color: '#e53935', fontSize: 13, marginBottom: 8 },
  grid: { flexDirection: 'row', flexWrap: 'wrap', width: 210, marginTop: 16, gap: 8 },
  key: {
    width: 62, height: 62, borderRadius: 31,
    backgroundColor: '#f0f0f7',
    justifyContent: 'center', alignItems: 'center',
  },
  keyHidden: { backgroundColor: 'transparent' },
  keyText: { fontSize: 22, fontWeight: '500', color: '#1a1a2e' },
  cancel: { marginTop: 20, color: '#5C6BC0', fontSize: 15 },
});
