# BlockApp — Bloqueur de sites Android

Application mobile Android de blocage de sites web par interception DNS.  
Backend Python (FastAPI + SQLite) · Frontend React Native (TypeScript) · Module natif Android (VpnService Java)

---

## Outils de développement nécessaires

| Outil | Rôle |
|---|---|
| **WSL2** (Windows Subsystem for Linux) | Environnement Linux sur Windows pour lancer le backend et builder l'app |
| **Node.js + npm** | Runtime JavaScript et gestionnaire de paquets pour React Native |
| **JDK 17** | Requis par Gradle pour compiler le code Android |
| **Android SDK** (command-line tools) | Outils de compilation Android (pas besoin d'Android Studio) |
| **Python + uv** | Runtime Python et gestionnaire de paquets pour le backend FastAPI |
| **ADB** (Android Debug Bridge) | Communication avec le téléphone Android pour déployer l'app |

---

## Structure du projet

```
backend/          → API FastAPI + SQLite
  main.py         → Routes API
  models.py       → Modèles de données
  database.py     → Connexion SQLite
  start.sh        → Script de démarrage

BlockApp/         → Application React Native
  src/            → Code TypeScript (écrans, composants, services)
  android/        → Code natif Android (VPN service en Java)
```

---

## Raccourcis Makefile

Depuis la racine du projet dans WSL2, des commandes `make` remplacent les commandes manuelles.

**Terminal : WSL2** (racine du projet)

| Commande | Effet |
|---|---|
| `make backend` | Lance le serveur FastAPI |
| `make metro` | Lance Metro (serveur JS React Native) |
| `make android` | Build et déploie l'app sur le téléphone |
| `make dev` | Ouvre backend + Metro dans **deux onglets** Windows Terminal en une seule commande |
| `make clean` | Supprime la BDD SQLite, les caches Python et les artefacts de build Android |

```bash
cd ~/projet-info/PROJ-blocage-site-web-app

make backend   # dans un terminal
make metro     # dans un autre terminal
make android   # dans un troisième terminal

# OU tout lancer d'un coup (requiert Windows Terminal) :
make dev
```

> `make dev` utilise `wt.exe` (Windows Terminal). Si Windows Terminal n'est pas installé : `winget install Microsoft.WindowsTerminal`

---

## Utilisation quotidienne

### 1. Lancer le backend

**Terminal : WSL2**
```bash
cd ~/projet-info/PROJ-blocage-site-web-app/backend
bash start.sh
```

Le backend tourne sur `http://0.0.0.0:8000`.  
Documentation interactive : `http://localhost:8000/docs`

---

### 2. Connecter le téléphone

Deux options selon la situation : USB (plus simple) ou Wi-Fi (sans câble).

#### Option A — USB (recommandé)

**Sur le téléphone** : Options développeur → **Débogage USB** → Activer  
Brancher le câble USB, accepter la popup "Autoriser le débogage" sur le téléphone.

**Terminal : PowerShell Windows**
```powershell
adb devices   # doit afficher "device" (pas "unauthorized")
```

Puis forwarder le port Metro directement depuis WSL2 grâce à `adb reverse` :

**Terminal : WSL2**
```bash
# Forwarder le port 8081 du téléphone vers WSL2 (fonctionne uniquement en USB)
adb reverse tcp:8081 tcp:8081
```

C'est tout — pas besoin de configurer l'IP dans l'app, Metro est accessible automatiquement via `localhost:8081` sur le téléphone.

> Si `adb` n'est pas reconnu dans WSL2, l'utiliser depuis PowerShell Windows à la place.

---

#### Option B — Wi-Fi (sans câble)

**Sur le téléphone** : Options développeur → **Débogage sans fil** → Activer  
→ noter l'IP et le port affichés (ex: `192.168.1.50:45678`)

**Terminal : PowerShell Windows**
```powershell
# Appairer (requis sur Android 11+, port affiché dans "Appairer l'appareil avec un code")
adb pair 192.168.1.50:<PORT_PAIRING>
# Saisir le code à 6 chiffres affiché sur le téléphone

# Connecter (port principal affiché en haut de l'écran "Débogage sans fil")
adb connect 192.168.1.50:<PORT_PRINCIPAL>
adb devices   # doit afficher "device"
```

> Les ports changent à chaque redémarrage — répéter pair + connect à chaque fois.

---

### 3. Lancer Metro (serveur JS)

**Terminal : WSL2**
```bash
cd ~/projet-info/PROJ-blocage-site-web-app/BlockApp
npx react-native start
```

---

### 4. Forwarder les ports vers le téléphone (Wi-Fi uniquement)

**USB** : déjà fait à l'étape 2 avec `adb reverse` — passer directement à l'étape 5.

**Wi-Fi uniquement** : `adb reverse` ne fonctionne pas en Wi-Fi. Il faut passer par Windows comme relais.

**Terminal : PowerShell Windows (admin)**
```powershell
# Trouver l'IP de WSL2
wsl hostname -I

# Forwarder le port 8081 (Metro) de Windows → WSL2
netsh interface portproxy add v4tov4 listenport=8081 listenaddress=0.0.0.0 connectport=8081 connectaddress=<IP_WSL2>

# Forwarder le port 8000 (backend FastAPI) de Windows → WSL2
netsh interface portproxy add v4tov4 listenport=8000 listenaddress=0.0.0.0 connectport=8000 connectaddress=<IP_WSL2>

# Ouvrir le firewall pour les deux ports (une seule fois)
netsh advfirewall firewall add rule name="Metro RN" dir=in action=allow protocol=TCP localport=8081
netsh advfirewall firewall add rule name="FastAPI BlockApp" dir=in action=allow protocol=TCP localport=8000
```

> L'IP WSL2 change à chaque redémarrage de Windows. Relancer `wsl hostname -I` et refaire les deux `portproxy add` si le backend n'est plus joignable.

**Sur le téléphone** : secouer → Settings → Debug server host → `<IP_WINDOWS>:8081`

---

Rendre miroir ADB entre Windows et WSL2 (pour que `adb` fonctionne dans les deux environnements) :

Dans le fichier `.config/wsl.conf` de WSL2 (`/etc/wsl.conf`) :


---

Il peut aussi falloir desactiver le pare-feu windows si WSL2 ne reconnait pas ADB en wi-fi (même après avoir ajouté les règles) :

Dans powerShell Windows (admin) :
```powershell
New-NetFirewallRule -DisplayName "WSL2 Backend 8001" -Direction Inbound -Protocol TCP -LocalPort 8001 -Action Allow
New-NetFirewallRule -DisplayName "WSL2 Metro 8081" -Direction Inbound -Protocol TCP -LocalPort 8081 -Action Allow
```

---

### 5. Déployer l'app sur le téléphone

**Terminal : WSL2** (dans un 3e terminal, Metro doit rester ouvert)
```bash
cd ~/projet-info/PROJ-blocage-site-web-app/BlockApp
npx react-native run-android
```

Après le premier déploiement, l'app reste installée. Il suffit de relancer Metro et de rouvrir l'app.

---

## Configuration

### IP du backend

Éditer [BlockApp/src/config.ts](BlockApp/src/config.ts) et remplacer l'IP :

```typescript
export const API_BASE_URL = 'http://<VOTRE_IP_WINDOWS>:8000/api';
```

Trouver votre IP Windows : `ipconfig` dans CMD → "Adresse IPv4" de la carte Wi-Fi.

---

## Prérequis sur le téléphone

- **Mode développeur** activé (Paramètres → À propos → Numéro de build × 7)
- **Débogage sans fil** activé (Options développeur)
- **DNS sécurisé Chrome désactivé** : Chrome → Paramètres → Confidentialité et sécurité → Utiliser un DNS sécurisé → **Désactivé**  
  *(Sans ça, Chrome contourne le blocage DNS — voir NetworkCourse.md)*

---

## Fonctionnalités

| Fonctionnalité | Détail |
|---|---|
| Ajouter un domaine | Bloque le domaine ET tous ses sous-domaines |
| 3 actions | Bloquer (DNS error) · Rediriger · Fermer |
| Groupes | Activer/désactiver un ensemble de sites d'un coup |
| Slider ON/OFF | Par site ou par groupe |
| PIN 4 chiffres | Requis pour passer un site de ON → OFF |
| Bouton VPN | Active/désactive tout le système de blocage |
