# Guide de création d'une app Android — Python + SQLite + React Native (TSX)

Ce guide documente toutes les étapes pour créer from scratch une application mobile Android avec :
- **Backend** : Python (FastAPI + SQLite), tournant sur Windows/WSL2
- **Frontend mobile** : React Native avec TypeScript (TSX)
- **Module natif** : Java (Android SDK)
- **Environnement** : Windows 11 + WSL2

---

## Partie 1 — Installer l'environnement de développement

### 1.1 WSL2 (Windows Subsystem for Linux)

WSL2 est un sous-système Linux intégré à Windows. On l'utilise pour le backend Python et les builds React Native.

**Terminal : PowerShell Windows (admin)**
```powershell
wsl --install
# Redémarrer Windows après l'installation
# Ubuntu est installé par défaut
```

Vérifier l'installation :
```powershell
wsl --list --verbose
# doit afficher Ubuntu avec VERSION 2
```

---

### 1.2 Node.js et npm

Node.js est requis pour React Native. L'installer dans WSL2.

**Terminal : WSL2**
```bash
# Installer nvm (gestionnaire de versions Node)
curl -o- https://raw.githubusercontent.com/nvm-sh/nvm/v0.39.7/install.sh | bash
source ~/.bashrc

# Installer la dernière version LTS de Node
nvm install --lts
nvm use --lts

# Vérifier
node --version
npm --version
```

---

### 1.3 JDK 17

Java 17 est requis par Gradle (le système de build Android). Versions inférieures incompatibles avec React Native 0.73+.

**Terminal : WSL2**
```bash
sudo apt update
sudo apt install openjdk-17-jdk -y

# Si plusieurs versions Java sont installées, sélectionner Java 17
sudo update-alternatives --config java
# Taper le numéro correspondant à java-17

# Vérifier
java -version
# doit afficher "openjdk version 17..."
```

---

### 1.4 Android SDK (sans Android Studio)

L'Android SDK contient les outils pour compiler et déployer des apps Android.

**Terminal : WSL2**
```bash
# Créer le dossier SDK
mkdir -p ~/Android/Sdk/cmdline-tools
cd ~/Android/Sdk/cmdline-tools

# Télécharger les command line tools (vérifier la dernière version sur developer.android.com/studio)
wget https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip

# Extraire (Gradle attend impérativement le nom "latest")
unzip commandlinetools-linux-13114758_latest.zip
mv cmdline-tools latest
rm commandlinetools-linux-13114758_latest.zip

# Ajouter au PATH (permanent)
echo 'export ANDROID_HOME=$HOME/Android/Sdk' >> ~/.bashrc
echo 'export PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools' >> ~/.bashrc
source ~/.bashrc

# Installer les composants nécessaires
sdkmanager --install "platform-tools" "platforms;android-35" "build-tools;35.0.0"

# Accepter toutes les licences
yes | sdkmanager --licenses
```

---

### 1.5 Python et uv

uv est un gestionnaire de paquets Python moderne et rapide, alternative à pip/venv.

**Terminal : WSL2**
```bash
# Installer uv
curl -LsSf https://astral.sh/uv/install.sh | sh
source ~/.bashrc

# Vérifier
uv --version
```

---

### 1.6 ADB (Android Debug Bridge)

ADB permet de communiquer avec le téléphone. Il est inclus dans platform-tools (déjà installé avec le SDK).  
Pour l'utiliser depuis **Windows PowerShell**, installer platform-tools séparément :

**Terminal : PowerShell Windows**
```powershell
winget install Google.PlatformTools
# OU télécharger manuellement depuis developer.android.com/tools/releases/platform-tools
```

---

## Partie 2 — Préparer le téléphone Android

### 2.1 Activer le mode développeur

Sur le téléphone : **Paramètres → À propos du téléphone → Numéro de build**  
Taper 7 fois sur "Numéro de build" → saisir le code PIN si demandé.  
→ "Vous êtes maintenant développeur !" apparaît.

### 2.2 Activer le débogage sans fil (Wi-Fi)

**Paramètres → Options développeur → Débogage sans fil** → Activer

L'écran affiche l'IP et le port de connexion. Garder cet écran visible pour la suite.

---

## Partie 3 — Créer le projet

### 3.1 Initialiser le dépôt Git

**Terminal : WSL2**
```bash
mkdir -p ~/projet-info/mon-app
cd ~/projet-info/mon-app
git init
```

### 3.2 Créer le backend Python

**Terminal : WSL2**
```bash
mkdir backend
cd backend
```

Créer `backend/requirements.txt` :
```
fastapi==0.115.0
uvicorn[standard]==0.30.6
sqlmodel==0.0.21
pydantic==2.9.2
```

Créer `backend/database.py` :
```python
from sqlmodel import SQLModel, create_engine, Session

DATABASE_URL = "sqlite:///./app.db"
engine = create_engine(DATABASE_URL, connect_args={"check_same_thread": False})

def create_db_and_tables():
    SQLModel.metadata.create_all(engine)

def get_session():
    with Session(engine) as session:
        yield session
```

Créer `backend/models.py` avec vos modèles SQLModel (voir exemple dans ce projet).

Créer `backend/main.py` avec l'application FastAPI.

Créer `backend/start.sh` :
```bash
#!/bin/bash
cd "$(dirname "$0")"
uv run --with fastapi --with "uvicorn[standard]" --with sqlmodel uvicorn main:app --host 0.0.0.0 --port 8000 --reload
```

Tester :
```bash
bash start.sh
# Ouvrir http://localhost:8000/docs dans le navigateur
```

---

### 3.3 Initialiser le projet React Native

**Important** : React Native CLI crée un nouveau dossier — ne pas lancer depuis l'intérieur du dossier cible.

**Terminal : WSL2**
```bash
cd ~/projet-info/mon-app

# Crée un dossier "MobileApp/" avec tout le boilerplate React Native
npx @react-native-community/cli init MobileApp

cd MobileApp
```

React Native 0.71+ inclut TypeScript par défaut, l'option `--template react-native-template-typescript` est ignorée.

---

### 3.4 Installer les dépendances supplémentaires

**Terminal : WSL2** (dans le dossier React Native)
```bash
npm install \
  @react-navigation/native \
  @react-navigation/bottom-tabs \
  @react-navigation/native-stack \
  react-native-screens \
  react-native-safe-area-context
```

> **Attention** : `@react-native-async-storage/async-storage` v1.x n'est pas compatible avec React Native 0.73+, et v2.x a des problèmes de dépendances Gradle. Éviter ce package — utiliser un fichier de config TypeScript à la place pour stocker des valeurs simples.

---

### 3.5 Configurer le fichier local.properties

Gradle a besoin de connaître l'emplacement du SDK Android.

**Terminal : WSL2**
```bash
echo "sdk.dir=$HOME/Android/Sdk" > android/app/../local.properties
# OU
echo "sdk.dir=$HOME/Android/Sdk" > android/local.properties
```

---

### 3.6 Corriger MainApplication (si module natif Java ajouté)

React Native 0.73+ génère un `MainApplication.kt` (Kotlin). Si vous ajoutez des modules natifs Java, ne pas créer un second `MainApplication.java` — modifier le `.kt` existant :

```kotlin
// android/app/src/main/java/com/<votre-package>/MainApplication.kt
packageList =
  PackageList(this).packages.apply {
    add(VotrePackage())  // Ajouter ici
  },
```

---

### 3.7 Configurer AndroidManifest.xml

Remplacer le fichier généré par votre version avec les permissions nécessaires.  
Pour un VPN : ajouter `android.permission.BIND_VPN_SERVICE` et déclarer le service.

---

## Partie 4 — Connecter le téléphone et déployer

Deux façons de connecter le téléphone : **USB** (plus simple, recommandé) ou **Wi-Fi** (sans câble).

---

### 4.1 Option A — Connexion USB (recommandée)

#### Prérequis téléphone

**Paramètres → Options développeur → Débogage USB** → Activer  
Brancher le câble USB, accepter la popup "Autoriser le débogage" sur le téléphone.

#### Vérifier la connexion

**Terminal : PowerShell Windows**
```powershell
adb devices
# doit afficher : <identifiant>  device
# si "unauthorized" → accepter la popup sur le téléphone
```

#### Forwarder Metro vers le téléphone

`adb reverse` redirige le port 8081 du téléphone vers le port 8081 de WSL2.  
C'est la grande simplicité du mode USB : **une seule commande suffit**, pas de config IP.

**Terminal : WSL2** (ou PowerShell Windows selon où `adb` est installé)
```bash
adb reverse tcp:8081 tcp:8081
```

Le téléphone peut maintenant joindre Metro via `localhost:8081` — aucun réglage supplémentaire dans l'app.

> Répéter `adb reverse` après chaque reconnexion USB ou redémarrage de Metro.

---

### 4.2 Option B — Connexion Wi-Fi (sans câble)

#### Prérequis téléphone

**Paramètres → Options développeur → Débogage sans fil** → Activer  
L'écran affiche l'IP et le port de connexion principal. Garder cet écran visible.

#### Connecter (Android 11+ : pairing obligatoire)

Android 11+ exige un "pairing" avec code avant la connexion. Il y a **deux ports différents** :
- Le port de **pairing** : affiché dans "Appairer l'appareil avec un code"
- Le port **principal** : affiché en haut de l'écran "Débogage sans fil"

**Terminal : PowerShell Windows**
```powershell
# Étape 1 : appairer avec le code à 6 chiffres
adb pair <IP_TELEPHONE>:<PORT_PAIRING>
# Entrer le code à 6 chiffres affiché sur le téléphone

# Étape 2 : connecter
adb connect <IP_TELEPHONE>:<PORT_PRINCIPAL>

# Vérifier
adb devices
# doit afficher : <identifiant>  device
```

> Les ports changent à chaque redémarrage. Répéter pair + connect à chaque fois.

#### Forwarder Metro vers le téléphone (Wi-Fi uniquement)

`adb reverse` ne fonctionne **pas** en Wi-Fi. Il faut faire passer Metro par Windows comme relais.

**Terminal : WSL2**
```bash
# Trouver l'IP de WSL2 (la noter)
ip addr show eth0 | grep "inet " | awk '{print $2}'
```

**Terminal : PowerShell Windows (admin)**
```powershell
# Forwarder port 8081 de Windows → WSL2
netsh interface portproxy add v4tov4 listenport=8081 listenaddress=0.0.0.0 connectport=8081 connectaddress=<IP_WSL2>

# Ouvrir le firewall (une seule fois)
netsh advfirewall firewall add rule name="Metro RN" dir=in action=allow protocol=TCP localport=8081

# Trouver l'IP Windows sur le Wi-Fi (chercher "Carte réseau sans fil Wi-Fi")
ipconfig
```

**Sur le téléphone** : secouer l'appareil → menu développeur React Native → **Settings** → **Debug server host & port for device** → saisir `<IP_WINDOWS>:8081`

---

### 4.3 Lancer Metro et déployer

Ouvrir **deux terminaux WSL2** :

**Terminal WSL2 n°1 — Metro (garder ouvert)**
```bash
cd MobileApp
npx react-native start
```

**Terminal WSL2 n°2 — Build et déploiement**
```bash
cd MobileApp
npx react-native run-android
```

Le premier build prend 10-30 minutes (téléchargement Gradle, compilation). Les suivants sont beaucoup plus rapides.

---

## Partie 5 — Problèmes courants rencontrés

### Gradle ne trouve pas le JDK

```
Gradle requires JVM 17 or later. Your build is currently configured to use JVM 11.
```
→ Installer JDK 17 et le définir comme défaut (`update-alternatives --config java`)

### SDK non trouvé

```
SDK location not found.
```
→ Créer `android/local.properties` avec `sdk.dir=/home/<user>/Android/Sdk`

### Dépendance Maven introuvable

```
Could not find org.asyncstorage.shared_storage:storage-android:1.0.0
```
→ Problème de compatibilité avec `@react-native-async-storage/async-storage`. Désinstaller ce package et éviter de l'utiliser avec React Native 0.84+.

### Redeclaration de MainApplication

```
Redeclaration: class MainApplication
```
→ React Native init a créé un `MainApplication.kt`. Ne pas ajouter un `MainApplication.java` en plus. Modifier uniquement le `.kt`.

### L'app ne trouve pas Metro

```
Unable to load script. Make sure you're running Metro...
```
→ Metro n'est pas lancé, OU le téléphone ne peut pas l'atteindre. Vérifier le portproxy et le "Debug server host" dans les settings de l'app.

### `adb reverse` ne fonctionne pas

→ `adb reverse` ne fonctionne qu'en USB, pas en Wi-Fi. Utiliser le portproxy Windows + configurer manuellement l'IP Metro sur le téléphone (voir 4.2).

---

## Récapitulatif des terminaux

| Quoi | Terminal |
|---|---|
| Installer SDK, Node, Java, uv | WSL2 |
| Connecter téléphone (adb pair/connect) | PowerShell Windows |
| Forwarder port Metro (netsh) | PowerShell Windows (admin) |
| Lancer le backend Python | WSL2 |
| Lancer Metro | WSL2 |
| Builder et déployer l'app | WSL2 |
