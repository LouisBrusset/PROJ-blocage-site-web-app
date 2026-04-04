BACKEND_DIR := $(CURDIR)/backend
MOBILE_DIR  := $(CURDIR)/BlockApp

.PHONY: help backend metro android dev clean

help:
	@echo ""
	@echo "  make backend   Lance le serveur FastAPI (port 8000)"
	@echo "  make metro     Lance Metro, le serveur JS React Native (port 8081)"
	@echo "  make android   Build et déploie l'app sur le téléphone Android"
	@echo "  make dev       Ouvre backend + Metro dans deux onglets Windows Terminal"
	@echo "  make clean     Supprime BDD, caches Python et artefacts de build Android"
	@echo ""

# ── Commandes individuelles ───────────────────────────────────────────────────

backend:
	cd "$(BACKEND_DIR)" && bash start.sh

metro:
	cd "$(MOBILE_DIR)" && npx react-native start

android:
	cd "$(MOBILE_DIR)" && npx react-native run-android

# ── Tout lancer en une commande ───────────────────────────────────────────────
# Ouvre deux onglets dans Windows Terminal (wt.exe) :
#   - Onglet 1 : backend FastAPI
#   - Onglet 2 : Metro
# Requiert Windows Terminal installé (winget install Microsoft.WindowsTerminal)

dev:
	wt.exe new-tab --title "Backend" -- wsl.exe bash -c "cd '$(BACKEND_DIR)' && bash start.sh; exec bash" \; new-tab --title "Metro" -- wsl.exe bash -c "cd '$(MOBILE_DIR)' && npx react-native start; exec bash"

# ── Nettoyage ─────────────────────────────────────────────────────────────────

clean:
	@echo "==> Suppression de la base de données SQLite..."
	rm -f "$(BACKEND_DIR)/blocker.db"
	@echo "==> Suppression des caches Python..."
	rm -rf "$(BACKEND_DIR)/__pycache__"
	rm -rf "$(BACKEND_DIR)/.uv"
	@echo "==> Suppression des artefacts de build Android..."
	rm -rf "$(MOBILE_DIR)/android/build"
	rm -rf "$(MOBILE_DIR)/android/app/build"
	rm -rf "$(MOBILE_DIR)/android/.gradle"
	@echo "==> Suppression du cache Metro..."
	rm -rf "$(MOBILE_DIR)/.metro"
	@echo ""
	@echo "Nettoyage terminé. La base de données et les caches ont été supprimés."
	@echo "Les node_modules et le SDK Android sont conservés."
