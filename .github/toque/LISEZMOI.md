# Reconstruction automatique de Toque

Le workflow `.github/workflows/toque-auto-rebuild.yml` tourne chaque jour (vers 05:17 UTC). Quand
Wuki60 publie un nouveau tag `vX.Y.Z`, il :

1. crée la branche `toque-combo-XYZ` depuis ce tag et y fusionne la dernière `toque-combo-NNN`
   (domotique, Cookomix, Jow) ;
2. résout seul un seul type de conflit : les lignes `versionCode` / `versionName` ;
3. écrit la version `X.Y.Z-<suffixe actuel>` et un `versionCode` supérieur à la fois à l'installé
   et à celui de l'amont ;
4. lance `:core:test`, compile l'APK, vérifie que la clé de signature est celle du robot ;
5. pousse la branche (jamais en force, jamais par-dessus une branche existante) et ouvre une issue
   avec l'empreinte SHA-256 et le lien vers l'APK.

Rien n'est publié en release ni installé. En cas de conflit, d'échec des tests ou de clé différente,
une issue décrit le problème et aucune branche n'est poussée.

## Réglages nécessaires dans le fork

- **Issues activées** (Settings › General › Features › Issues) : elles servent d'alerte.
- **Secret `TOQUE_PUSH_TOKEN`** (facultatif mais conseillé) : jeton « fine-grained » limité à ce
  dépôt, droits *Contents : Read and write* et *Workflows : Read and write*. GitHub refuse à
  `GITHUB_TOKEN` de pousser des commits qui touchent `.github/workflows`, ce qui arrive dès que
  l'amont modifie ses workflows. Sans ce secret, l'issue l'indique et le paquet `toque.bundle`
  reste dans l'artefact « reconstruction ».
- Les exécutions planifiées s'arrêtent d'elles-mêmes après 60 jours sans activité sur le dépôt :
  GitHub propose alors de les réactiver dans l'onglet Actions.

## Lancer à la main

Actions › « Reconstruire Toque sur la dernière version amont » › Run workflow. Champs facultatifs :
tag amont, branche source, et **Essai** (compile une branche `-essai`, sans pousser ni ouvrir
d'issue ; la version porte le suffixe `-essai`, à ne pas installer).

## Essai local

```sh
OUT=/dev/stdout REPORT=/tmp/rapport.md DRY_RUN=true bash .github/toque/rebuild.sh
```
