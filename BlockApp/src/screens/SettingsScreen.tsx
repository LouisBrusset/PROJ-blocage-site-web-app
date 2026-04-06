import React, { useEffect, useState } from 'react';
import {
  View, Text, TextInput, TouchableOpacity, StyleSheet,
  Alert, ScrollView, Switch,
} from 'react-native';
import { hasPin, setPin, removePin, verifyPin } from '../services/api';
import { WifiMonitor, requestWifiPermissions } from '../services/wifi';
import PinModal from '../components/PinModal';

export default function SettingsScreen() {
  const [pinExists, setPinExists] = useState(false);
  const [pinModalMode, setPinModalMode] = useState<'verify-remove' | null>(null);
  const [newPin, setNewPin] = useState('');
  const [confirmPin, setConfirmPin] = useState('');

  // Wi-Fi monitor state
  const [wifiMonitorEnabled, setWifiMonitorEnabled] = useState(false);
  const [currentSsid, setCurrentSsid] = useState<string | null>(null);
  const [trustedNetworks, setTrustedNetworks] = useState<string[]>([]);

  useEffect(() => {
    hasPin().then(r => setPinExists(r.has_pin)).catch(() => {});
    loadWifiState();
  }, []);

  const loadWifiState = async () => {
    try {
      const [ssid, trusted, wasEnabled] = await Promise.all([
        WifiMonitor.getCurrentSsid(),
        WifiMonitor.getTrustedNetworks(),
        WifiMonitor.isMonitoringEnabled(),
      ]);
      setCurrentSsid(ssid);
      setTrustedNetworks(trusted);
      if (wasEnabled) {
        // Redémarre le service si la surveillance était active lors de la dernière session
        await WifiMonitor.start();
        setWifiMonitorEnabled(true);
      }
    } catch {}
  };

  const toggleWifiMonitor = async (enabled: boolean) => {
    if (enabled) {
      const granted = await requestWifiPermissions();
      if (!granted) {
        Alert.alert(
          'Permission requise',
          'L\'accès à la localisation est nécessaire pour lire le nom du Wi-Fi (SSID).',
        );
        return;
      }
      await WifiMonitor.start();
      setWifiMonitorEnabled(true);
      loadWifiState();
    } else {
      await WifiMonitor.stop();
      setWifiMonitorEnabled(false);
    }
  };

  const trustCurrentNetwork = async () => {
    if (!currentSsid) return;
    await WifiMonitor.addTrustedNetwork(currentSsid);
    setTrustedNetworks(prev => [...prev.filter(s => s !== currentSsid.toLowerCase()), currentSsid.toLowerCase()]);
    Alert.alert('Réseau de confiance', `"${currentSsid}" ajouté à tes réseaux de confiance.`);
  };

  const removeTrusted = async (ssid: string) => {
    await WifiMonitor.removeTrustedNetwork(ssid);
    setTrustedNetworks(prev => prev.filter(s => s !== ssid));
  };

  const handleSetPin = async () => {
    if (newPin.length !== 4 || !/^\d+$/.test(newPin)) {
      Alert.alert('Erreur', 'Le PIN doit contenir exactement 4 chiffres.');
      return;
    }
    if (newPin !== confirmPin) {
      Alert.alert('Erreur', 'Les PINs ne correspondent pas.');
      return;
    }
    try {
      await setPin(newPin);
      setPinExists(true);
      setNewPin('');
      setConfirmPin('');
      Alert.alert('PIN défini', 'Le PIN a été enregistré. Il sera requis pour désactiver un blocage.');
    } catch (e: any) {
      Alert.alert('Erreur', e.message);
    }
  };

  const handleRemovePin = () => {
    setPinModalMode('verify-remove');
  };

  return (
    <ScrollView style={s.container} contentContainerStyle={s.content}>
      {/* PIN */}
      <Text style={s.section}>Sécurité PIN</Text>
      <View style={s.card}>
        <Text style={s.hint}>
          Le PIN est requis pour désactiver un site bloqué (passer de ON à OFF).{'\n'}
          Cela évite de contourner facilement les blocages.
        </Text>

        {!pinExists ? (
          <>
            <Text style={s.label}>Définir un PIN (4 chiffres)</Text>
            <TextInput
              style={s.input}
              value={newPin}
              onChangeText={t => setNewPin(t.replace(/\D/g, '').slice(0, 4))}
              placeholder="****"
              keyboardType="numeric"
              secureTextEntry
              maxLength={4}
            />
            <Text style={s.label}>Confirmer le PIN</Text>
            <TextInput
              style={s.input}
              value={confirmPin}
              onChangeText={t => setConfirmPin(t.replace(/\D/g, '').slice(0, 4))}
              placeholder="****"
              keyboardType="numeric"
              secureTextEntry
              maxLength={4}
            />
            <TouchableOpacity style={s.btn} onPress={handleSetPin}>
              <Text style={s.btnText}>Définir le PIN</Text>
            </TouchableOpacity>
          </>
        ) : (
          <View style={s.row}>
            <Text style={s.pinStatus}>✅ PIN actif</Text>
            <TouchableOpacity style={s.removeBtn} onPress={handleRemovePin}>
              <Text style={s.removeBtnText}>Supprimer le PIN</Text>
            </TouchableOpacity>
          </View>
        )}
      </View>

      {/* Info VPN */}
      <Text style={s.section}>À propos du blocage</Text>
      <View style={s.card}>
        <Text style={s.hint}>
          Le blocage fonctionne via un VPN local qui intercepte les requêtes DNS.{'\n\n'}
          <Text style={{ fontWeight: '700' }}>Important :</Text> désactivez le "DNS sécurisé" dans Chrome :{'\n'}
          Chrome → Paramètres → Confidentialité → DNS sécurisé → Désactivé.{'\n\n'}
          Sans cette étape, Chrome peut contourner le blocage DNS.
        </Text>
      </View>

      {/* Wi-Fi monitor */}
      <Text style={s.section}>Sécurité Wi-Fi public</Text>
      <View style={s.card}>
        <Text style={s.hint}>
          Comme le DNS sécurisé Chrome est désactivé, tes requêtes DNS ne sont pas chiffrées
          sur les réseaux publics.{'\n'}
          Active la surveillance pour recevoir une alerte sur chaque Wi-Fi inconnu.
        </Text>

        <View style={[s.row, { marginTop: 14 }]}>
          <Text style={[s.label, { marginTop: 0, flex: 1 }]}>Surveillance active</Text>
          <Switch
            value={wifiMonitorEnabled}
            onValueChange={toggleWifiMonitor}
            trackColor={{ true: '#5C6BC0', false: '#ccc' }}
            thumbColor="#fff"
          />
        </View>

        {currentSsid ? (
          <View style={s.ssidRow}>
            <View style={s.ssidInfo}>
              <Text style={s.ssidLabel}>Réseau actuel</Text>
              <Text style={s.ssidValue}>{currentSsid}</Text>
            </View>
            {!trustedNetworks.includes(currentSsid.toLowerCase()) ? (
              <TouchableOpacity style={s.trustBtn} onPress={trustCurrentNetwork}>
                <Text style={s.trustBtnText}>Marquer sûr</Text>
              </TouchableOpacity>
            ) : (
              <Text style={s.trustedBadge}>✅ Sûr</Text>
            )}
          </View>
        ) : (
          <Text style={[s.hint, { marginTop: 8 }]}>Aucun Wi-Fi connecté</Text>
        )}
      </View>

      {trustedNetworks.length > 0 && (
        <>
          <Text style={s.section}>Réseaux de confiance</Text>
          <View style={s.card}>
            {trustedNetworks.map(ssid => (
              <View key={ssid} style={s.trustedRow}>
                <Text style={s.trustedSsid}>📶 {ssid}</Text>
                <TouchableOpacity onPress={() => removeTrusted(ssid)}>
                  <Text style={s.removeText}>Retirer</Text>
                </TouchableOpacity>
              </View>
            ))}
          </View>
        </>
      )}

      {/* PIN modal for removing PIN */}
      <PinModal
        visible={pinModalMode === 'verify-remove'}
        title="Entrez votre PIN actuel"
        onSuccess={() => setPinModalMode(null)}
        onCancel={() => setPinModalMode(null)}
        onVerify={async (pin) => {
          const r = await verifyPin(pin);
          if (r.valid) {
            await removePin(pin);
            setPinExists(false);
          }
          return r.valid;
        }}
      />
    </ScrollView>
  );
}

const s = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#f4f4fb' },
  content: { padding: 16, paddingBottom: 60 },
  section: {
    fontSize: 13, fontWeight: '700', color: '#5C6BC0',
    textTransform: 'uppercase', letterSpacing: 0.8,
    marginTop: 24, marginBottom: 10, marginLeft: 4,
  },
  card: {
    backgroundColor: '#fff', borderRadius: 14, padding: 16,
    shadowColor: '#000', shadowOpacity: 0.05, shadowRadius: 6, elevation: 2,
  },
  label: { fontSize: 13, fontWeight: '600', color: '#444', marginBottom: 6, marginTop: 12 },
  input: {
    backgroundColor: '#f4f4fb', borderRadius: 10, padding: 14,
    fontSize: 15, color: '#1a1a2e',
  },
  hint: { fontSize: 13, color: '#888', lineHeight: 20, marginTop: 8 },
  urlDisplay: { fontSize: 13, color: '#5C6BC0', fontFamily: 'monospace', marginTop: 6, padding: 10, backgroundColor: '#f0f0f7', borderRadius: 8 },
  btn: {
    marginTop: 14, backgroundColor: '#5C6BC0', borderRadius: 10,
    padding: 14, alignItems: 'center',
  },
  btnText: { color: '#fff', fontWeight: '700', fontSize: 15 },
  row: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', marginTop: 10 },
  pinStatus: { fontSize: 15, color: '#43A047' },
  removeBtn: { padding: 10, borderRadius: 8, backgroundColor: '#fce4e4' },
  removeBtnText: { color: '#E53935', fontWeight: '600' },
  // Wi-Fi styles
  ssidRow: {
    flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between',
    marginTop: 12, paddingTop: 12, borderTopWidth: 1, borderTopColor: '#f0f0f7',
  },
  ssidInfo: { flex: 1 },
  ssidLabel: { fontSize: 11, color: '#aaa', textTransform: 'uppercase', letterSpacing: 0.5 },
  ssidValue: { fontSize: 15, fontWeight: '600', color: '#1a1a2e', marginTop: 2 },
  trustBtn: { backgroundColor: '#5C6BC0', paddingHorizontal: 12, paddingVertical: 8, borderRadius: 8 },
  trustBtnText: { color: '#fff', fontWeight: '600', fontSize: 13 },
  trustedBadge: { fontSize: 13, color: '#43A047' },
  trustedRow: {
    flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between',
    paddingVertical: 10, borderBottomWidth: 1, borderBottomColor: '#f0f0f7',
  },
  trustedSsid: { fontSize: 14, color: '#1a1a2e', flex: 1 },
  removeText: { fontSize: 13, color: '#E53935', fontWeight: '600' },
});
