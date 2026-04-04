# Cours réseau — Comment fonctionne le blocage de sites web

Ce document explique les mécanismes réseau qui permettent à BlockApp de bloquer des sites, pourquoi certaines limitations existent, et comment les dépasser à l'avenir.

---

## 1. Qu'est-ce que le DNS ?

### Définition

DNS signifie **Domain Name System** — système de noms de domaine. C'est l'annuaire téléphonique d'Internet.

Les ordinateurs communiquent entre eux via des **adresses IP** (ex: `142.250.74.46`). Les humains, eux, tapent des noms lisibles comme `youtube.com`. Le DNS fait la traduction entre les deux.

### Comment ça fonctionne concrètement

Quand vous tapez `youtube.com` dans Chrome, voici ce qui se passe :

```
1. Chrome demande : "Quelle est l'adresse IP de youtube.com ?"
2. La requête DNS est envoyée vers un serveur DNS (souvent celui de votre box, ou 8.8.8.8 pour Google)
3. Le serveur DNS répond : "youtube.com → 142.250.74.46"
4. Chrome ouvre une connexion TCP vers 142.250.74.46 sur le port 443 (HTTPS)
5. La page se charge
```

### Protocole technique

- Les requêtes DNS utilisent le protocole **UDP** (User Datagram Protocol), port **53**
- Format binaire compact : un paquet DNS contient le nom demandé, le type de réponse souhaitée (A pour IPv4, AAAA pour IPv6, MX pour mail, etc.)
- La réponse contient une ou plusieurs adresses IP avec un **TTL** (Time To Live) — durée de mise en cache

### Types de réponses DNS importantes

| Code | Nom | Signification |
|---|---|---|
| `NOERROR` | Succès | Le domaine existe, voici son IP |
| `NXDOMAIN` | Non-existent Domain | Le domaine n'existe pas |
| `SERVFAIL` | Server Failure | Le serveur DNS a eu une erreur |

**BlockApp renvoie `NXDOMAIN`** pour les domaines bloqués. Chrome voit "le domaine n'existe pas" et affiche "Ce site est inaccessible - ERR_NAME_NOT_RESOLVED".

---

## 2. Qu'est-ce qu'un VPN local ?

### VPN classique (commercial)

Un VPN (Virtual Private Network) crée un tunnel chiffré entre votre appareil et un serveur distant. Tout votre trafic passe par ce serveur, qui le transmet à Internet à votre place.

**Utilisation habituelle** : anonymat, contournement de censure géographique.

### VPN local (ce que fait BlockApp)

Un VPN local utilise la même infrastructure technique (l'API `VpnService` d'Android) mais **sans serveur distant**. Le tunnel pointe vers le téléphone lui-même.

Android fournit l'API `VpnService` qui permet à une application de :
1. Créer une **interface réseau virtuelle** appelée TUN (TUNnel)
2. Demander au système de **router certains paquets** vers cette interface
3. **Lire et écrire ces paquets** directement dans le code Java

```
Sans VPN :
  Chrome → [réseau Wi-Fi] → Box → Internet → youtube.com

Avec BlockApp VPN :
  Chrome → [interface TUN virtuelle] → VpnBlockService.java → [décision : bloquer ou passer]
                                                             → si non bloqué → Box → Internet
```

### Comment BlockApp route le trafic

BlockApp utilise une technique ciblée : au lieu de faire passer **tout** le trafic par le TUN (coûteux), elle ne route que les **requêtes DNS** :

```java
// Dans VpnBlockService.java
new Builder()
    .addAddress("10.0.0.1", 24)    // Adresse virtuelle du TUN
    .addRoute("10.0.0.2", 32)      // Router SEULEMENT le trafic vers 10.0.0.2
    .addDnsServer("10.0.0.2")      // Dire au système : utilise 10.0.0.2 comme DNS
```

Le système Android envoie donc toutes les requêtes DNS vers `10.0.0.2`. Ces paquets arrivent dans le TUN, et `VpnBlockService.java` les intercepte pour :
- **Domaine bloqué** → répondre NXDOMAIN
- **Autre domaine** → transférer à `8.8.8.8` (vrai DNS Google) et relayer la réponse

---

## 3. Comment Chrome gère les DNS — et pourquoi c'est un problème

### Le DNS classique (ce qu'on intercepte)

Par défaut, les applications Android (y compris Chrome) utilisent le DNS configuré par le système — qui est celui fourni par votre box ou votre opérateur. Notre VPN remplace ce DNS par `10.0.0.2`, ce qui nous permet de l'intercepter.

### DNS-over-HTTPS (DoH) — le contournement

Chrome intègre une fonctionnalité appelée **DNS sécurisé** (ou DNS-over-HTTPS, DoH). Au lieu d'envoyer les requêtes DNS en clair sur le port 53 (UDP), Chrome les envoie **chiffrées dans des requêtes HTTPS** vers un serveur DoH (par exemple `https://dns.google/dns-query`).

Ce que ça change :
```
Sans DoH (DNS classique) :
  Chrome → UDP port 53 → notre VPN intercepte ✓

Avec DoH (DNS sécurisé) :
  Chrome → HTTPS port 443 → dns.google → NXDOMAIN jamais renvoyé ✗
```

Notre VPN ne voit qu'une connexion HTTPS chiffrée vers `8.8.8.8:443`. Il ne peut pas lire son contenu, et ne sait pas que c'est une requête DNS.

### Pourquoi Chrome active DoH par défaut

Chrome justifie cette décision par la sécurité : le DNS classique (UDP port 53) est en clair et peut être espionné ou falsifié par des intermédiaires (opérateur, routeur). DoH chiffre les requêtes DNS et les authentifie, empêchant leur interception ou leur modification.

C'est techniquement correct — mais cela brise aussi tous les outils légitimes qui utilisent l'interception DNS (contrôle parental, blocage réseau d'entreprise, bloqueurs de pub, etc.).

### La limitation de BlockApp

**Il faut désactiver manuellement le DNS sécurisé dans Chrome** pour que BlockApp fonctionne :

`Chrome → ⋮ → Paramètres → Confidentialité et sécurité → Utiliser un DNS sécurisé → Désactivé`

C'est une limitation réelle. Si vous oubliez de le désactiver, ou si Chrome le réactive (après une mise à jour par exemple), le blocage ne fonctionne plus.

---

## 4. Pourquoi désactiver le DNS sécurisé est problématique

Désactiver DoH dans Chrome expose vos requêtes DNS à :

- **Votre opérateur Internet** : il peut voir tous les domaines que vous visitez (même si le contenu des pages est chiffré par HTTPS)
- **Votre box/routeur** : un attaquant sur le même réseau Wi-Fi pourrait théoriquement falsifier des réponses DNS (attaque "DNS spoofing") pour vous rediriger vers des faux sites

Dans le contexte de BlockApp (usage personnel, réseau domestique de confiance), ce risque est faible. Mais c'est une dégradation de la sécurité à avoir en tête.

---

## 5. Évolution future : VPN complet avec proxy TCP

### Limitation actuelle

BlockApp intercepte uniquement les DNS. Cela signifie :
- Chrome ne charge pas la page → affiche "Ce site est inaccessible" (erreur DNS)
- Impossible d'afficher une page personnalisée de blocage pour les sites HTTPS
- Contournable si DoH est activé

### Solution future : interception complète du trafic

Pour un blocage plus robuste et des actions personnalisées (page "BLOQUÉ", redirection réelle), il faudrait faire passer **tout le trafic TCP** par le VPN, pas seulement le DNS.

Architecture :

```
Chrome → [TUN - tout le trafic] → VpnService
                                    ↓
                              Proxy TCP local
                                    ↓
                          ┌─────────────────────┐
                          │ Domaine bloqué ?     │
                          │  Oui → page BLOQUÉ  │
                          │  Non → Internet      │
                          └─────────────────────┘
```

**Composants nécessaires** :

| Composant | Rôle |
|---|---|
| Interface TUN avec route `0.0.0.0/0` | Intercepter TOUT le trafic |
| Bibliothèque **tun2socks** | Convertir les paquets TUN bruts en connexions SOCKS5 |
| Proxy SOCKS5 local | Examiner les connexions, décider de bloquer ou relayer |
| Serveur HTTP local | Servir la page "BLOQUÉ" |
| **Certificat SSL auto-signé** installé sur le téléphone | Intercepter HTTPS (Man-in-the-Middle local) |

**Pourquoi c'est complexe** :

1. **HTTPS chiffre le contenu** : pour voir le nom de domaine dans une connexion HTTPS (et donc décider de la bloquer), il faut faire du SSL interception — le proxy présente un faux certificat au navigateur, déchiffre le trafic, puis le rechiffre vers le vrai serveur.

2. **L'utilisateur doit installer le certificat CA** : pour que le navigateur accepte le faux certificat, il faut installer votre propre autorité de certification dans le magasin de certificats Android — manipulation manuelle requise.

3. **Android 7+ restreint les CA utilisateur** : depuis Android 7, les applications n'acceptent plus les certificats installés par l'utilisateur par défaut. Chrome et les apps réseau modernes ignorent les CA utilisateur sauf configuration explicite dans le manifeste de l'app.

4. **tun2socks** : bibliothèque native C (ou Go) qui transforme les paquets IP bruts lus sur le TUN en connexions TCP normales utilisables depuis Java/Kotlin. À intégrer comme bibliothèque native dans le projet Android.

**Avantage** : fonctionne sans désactiver DoH, affiche une vraie page de blocage, bloque aussi les apps non-navigateur.

**Implémentations de référence** : AdGuard pour Android, 1.1.1.1 (Cloudflare), NetGuard — toutes utilisent cette architecture.

---

## Résumé

| Mécanisme | BlockApp actuel | VPN complet (futur) |
|---|---|---|
| Ce qui est intercepté | DNS (UDP port 53) | Tout le trafic TCP/UDP |
| Contournable par DoH | Oui | Non |
| Page de blocage personnalisée HTTP | Possible | Oui |
| Page de blocage personnalisée HTTPS | Non | Oui (avec certificat CA) |
| Complexité | Faible | Élevée |
| Fonctionne sans configuration Chrome | Non | Oui |
